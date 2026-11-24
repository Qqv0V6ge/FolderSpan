/* 模拟公开传输接口，验证心跳的响应、超时、断线及延迟回调所有权。 */
#include <assert.h>
#include <errno.h>
#include <stdlib.h>

static int allocations;
static int fail_allocation;

static void *test_calloc(size_t count, size_t size)
{
    if (fail_allocation) return NULL;
    void *value = calloc(count, size);
    if (value != NULL) allocations++;
    return value;
}

static void test_free(void *value)
{
    if (value != NULL) allocations--;
    free(value);
}

#define calloc test_calloc
#define free test_free
#include "smb2_wrapper.h"
#undef calloc
#undef free

struct smb2_context { int fd; };
struct transport_step {
    int poll_result;
    int poll_error;
    short events;
    int service_result;
    int completes;
    int status;
    int closes_socket;
};

static const struct transport_step *steps;
static int step_count;
static int poll_count;
static int service_count;
static int start_result;
static smb2_command_cb pending_callback;
static void *pending_state;

int smb2_get_fd(struct smb2_context *context) { return context->fd; }
int smb2_which_events(struct smb2_context *context) { (void)context; return POLLIN; }

int smb2_echo_async(struct smb2_context *context, smb2_command_cb callback, void *state)
{
    (void)context;
    if (start_result < 0) return start_result;
    pending_callback = callback;
    pending_state = state;
    return 0;
}

int poll(struct pollfd *descriptors, nfds_t count, int timeout)
{
    assert(count == 1 && timeout == 1000 && poll_count < step_count);
    const struct transport_step *step = &steps[poll_count++];
    descriptors[0].revents = step->events;
    errno = step->poll_error;
    return step->poll_result;
}

int smb2_service(struct smb2_context *context, int events)
{
    const struct transport_step *step = &steps[poll_count - 1];
    assert(events == (step->poll_result < 0 ? 0 : step->events));
    service_count++;
    if (step->closes_socket) context->fd = -1;
    if (step->completes) {
        pending_callback(context, step->status, NULL, pending_state);
        pending_callback = NULL;
    }
    return step->service_result;
}

static void verify(const struct transport_step *scenario, int count, int expected)
{
    struct smb2_context context = {7};
    steps = scenario;
    step_count = count;
    poll_count = service_count = 0;
    assert(folderspan_smb2_echo(&context) == expected);
    assert(poll_count == count);
    if (pending_callback != NULL) {
        /* 模拟缓存淘汰会话后，libsmb2 销毁上下文时补发的回调。 */
        assert(allocations == 1);
        pending_callback(&context, -ECONNRESET, NULL, pending_state);
        pending_callback = NULL;
    }
    assert(allocations == 0);
}

int main(void)
{
    struct smb2_context disconnected = {-1};
    assert(folderspan_smb2_echo(&disconnected) == -ENOTCONN);
    fail_allocation = 1;
    verify(NULL, 0, -ENOMEM);
    fail_allocation = 0;
    start_result = -ENOMEM;
    verify(NULL, 0, -ENOMEM);
    start_result = 0;

    const struct transport_step success[] = {{1, 0, POLLIN, 0, 1, 0, 0}};
    verify(success, 1, 0);
    const struct transport_step rejected[] = {{1, 0, POLLIN, 0, 1, -EACCES, 0}};
    verify(rejected, 1, -EACCES);
    const struct transport_step timeout[] = {
        {0, 0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 1, -ETIMEDOUT, 0},
    };
    verify(timeout, 2, -ETIMEDOUT);
    assert(service_count == 2);
    const struct transport_step interrupted[] = {
        {-1, EINTR, POLLIN, 0, 0, 0, 0}, {1, 0, POLLIN, 0, 1, 0, 0},
    };
    verify(interrupted, 2, 0);
    assert(service_count == 2);
    const struct transport_step poll_failure[] = {{-1, EBADF, 0, 0, 0, 0, 0}};
    verify(poll_failure, 1, -EBADF);
    assert(service_count == 0);
    const struct transport_step reset[] = {{1, 0, POLLHUP, -EIO, 0, 0, 0}};
    verify(reset, 1, -EIO);
    const struct transport_step completed_then_reset[] = {{1, 0, POLLHUP, -EIO, 1, 0, 0}};
    verify(completed_then_reset, 1, -EIO);
    const struct transport_step closed_socket[] = {{0, 0, 0, 0, 0, 0, 1}};
    verify(closed_socket, 1, -ENOTCONN);
    return 0;
}
