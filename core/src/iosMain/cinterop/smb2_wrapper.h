#ifndef SMB2_CINTEROP_WRAPPER_H
#define SMB2_CINTEROP_WRAPPER_H

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>
#include <errno.h>
#include <poll.h>
#include <stdlib.h>
#include <smb2/smb2.h>
#include <smb2/libsmb2.h>

/* 心跳失败后仍可能收到销毁回调，状态必须存活到回调完成。 */
struct folderspan_smb2_echo_state {
    int finished;
    int status;
    int abandoned;
};

static inline void folderspan_smb2_echo_callback(
    struct smb2_context *smb2, int status, void *command_data, void *private_data)
{
    (void)smb2;
    (void)command_data;
    struct folderspan_smb2_echo_state *state = private_data;
    if (state->abandoned) {
        free(state);
        return;
    }
    state->status = status;
    state->finished = 1;
}

/* 绕过 libsmb2 6.2 同步 ECHO 的反向 socket 判断；调用方须设置命令超时并独占上下文。 */
static inline int folderspan_smb2_echo(struct smb2_context *smb2)
{
    if (smb2_get_fd(smb2) < 0) {
        return -ENOTCONN;
    }
    struct folderspan_smb2_echo_state *state = calloc(1, sizeof(*state));
    if (state == NULL) {
        return -ENOMEM;
    }
    int result = smb2_echo_async(smb2, folderspan_smb2_echo_callback, state);
    if (result < 0) {
        free(state);
        return result;
    }
    while (!state->finished) {
        struct pollfd socket = {smb2_get_fd(smb2), (short)smb2_which_events(smb2), 0};
        if (socket.fd < 0) {
            result = -ENOTCONN;
            break;
        }
        if (poll(&socket, 1, 1000) < 0) {
            if (errno != EINTR) {
                result = -errno;
                break;
            }
            socket.revents = 0;
        }
        /* 无事件或被信号中断时也处理超时，避免服务器无响应时无限等待。 */
        result = smb2_service(smb2, socket.revents);
        if (result < 0) {
            break;
        }
    }
    if (state->finished) {
        if (result >= 0) result = state->status;
        free(state);
    } else {
        /* 缓存会淘汰失败会话；延迟回调在断开或销毁上下文时释放状态。 */
        state->abandoned = 1;
    }
    return result;
}

#endif
