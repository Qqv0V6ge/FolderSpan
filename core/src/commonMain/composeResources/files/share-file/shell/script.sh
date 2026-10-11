#!/bin/bash

# API服务器地址设为常量
API_SERVER="#API_SERVER#"

# User-Agent常量
USER_AGENT="#USER_AGENT#"

# 链接分享会话
LINK_SHARE_SESSION_HEADER="#LINK_SHARE_SESSION_HEADER#"
LINK_SHARE_SESSION_TOKEN="#LINK_SHARE_SESSION_TOKEN#"

# 设置本地目标目录为当前目录
TARGET_DIR="./#TARGET_DIR#"

# URL 解码函数
url_decode() {
  local url="$1"
  # 使用 printf 和 sed 进行 URL 解码
  printf '%b' "${url//%/\\x}"
}

# 如果目标目录不存在，则创建它
if [ ! -d "$TARGET_DIR" ]; then
    echo "目标目录($TARGET_DIR)不存在，将自动创建..."
    mkdir -p "$TARGET_DIR"
fi

# 如果目标目录不为空，则要求用户确认
if [ "$(ls -A "$TARGET_DIR" 2>/dev/null)" ]; then
  echo "警告: 当前目录不是空目录，下载操作可能会覆盖现有文件。"
  echo "按回车键继续，或按任意其他键取消操作..."
  read -n 1 key
  if [ "$key" != "" ]; then
    echo "操作已取消。"
    exit 1
  fi
  echo "用户已确认，将继续覆盖下载..."
fi

# 初始化全局计数器
TOTAL_FILES=0
TOTAL_DIRS=0
SUCCESS_FILES=0
FAILED_FILES=0
SUCCESS_DIRS=0
FAILED_DIRS=0

# 递归处理文件和目录的函数
process_files_and_directories() {
    local remote_path=$1
    local local_path=$2
    # URL 解码 remote_path
    local decoded_remote_path=$(url_decode "$remote_path")

    echo "处理路径: $decoded_remote_path -> $local_path"

    # 发送 HTTP 请求获取当前路径的内容 (curl 参数不做 URL 解码)
    response=$(curl -s -H "X-API-Request: true" -H "User-Agent: ${USER_AGENT}" -H "${LINK_SHARE_SESSION_HEADER}: ${LINK_SHARE_SESSION_TOKEN}" "${API_SERVER}${remote_path}")

    # 检查响应是否有效
    if ! echo "$response" | jq -e . >/dev/null 2>&1; then
        echo "错误: 无法解析路径 $decoded_remote_path 的响应"
        return 1
    fi

    while read -r item; do
        # 跳过空行
        [ -z "$item" ] && continue

        name=$(echo "$item" | jq -r '.name')
        # URL 解码文件名
        decoded_name=$(url_decode "$name")

        is_dir=$(echo "$item" | jq -r '.isDirectory')
        item_path=$(echo "$item" | jq -r '.path')
        # 用解码后的名称创建本地路径
        local_item_path="$local_path/$decoded_name"

        if [ "$is_dir" == "true" ]; then
            # 如果是目录，创建对应的本地目录
            echo "创建目录: $local_item_path"
            ((TOTAL_DIRS++))

            if mkdir -p "$local_item_path"; then
                ((SUCCESS_DIRS++))
            else
                echo "错误: 无法创建目录 $local_item_path"
                ((FAILED_DIRS++))
                continue
            fi

            # 递归处理子目录
            process_files_and_directories "$item_path" "$local_item_path"

        else
            # 显示解码后的路径
            decoded_item_path=$(url_decode "$item_path")
            echo "下载文件: $decoded_item_path -> $local_item_path"
            ((TOTAL_FILES++))

            # curl 命令使用原始路径（不解码）
            if curl -s -f -H "X-API-Request: true" -H "User-Agent: ${USER_AGENT}" -H "${LINK_SHARE_SESSION_HEADER}: ${LINK_SHARE_SESSION_TOKEN}" "${API_SERVER}${item_path}" -o "$local_item_path"; then
                ((SUCCESS_FILES++))
            else
                echo "错误: 无法下载文件 $decoded_item_path"
                ((FAILED_FILES++))
            fi
        fi
    done < <(echo "$response" | jq -c '.[]')
}

# 从根目录开始处理
echo "开始下载文件和创建目录到当前文件夹..."
root_path="#ROOT_PATH#"
process_files_and_directories "$root_path" "$TARGET_DIR"

# 打印统计结果
echo "========== 下载统计 =========="
echo "总文件夹数: $TOTAL_DIRS"
echo "成功创建的文件夹: $SUCCESS_DIRS"
echo "创建失败的文件夹: $FAILED_DIRS"
echo "总文件数: $TOTAL_FILES"
echo "成功下载的文件: $SUCCESS_FILES"
echo "下载失败的文件: $FAILED_FILES"
echo "============================="

# 计算总成功率
TOTAL_ITEMS=$((TOTAL_FILES + TOTAL_DIRS))
SUCCESS_ITEMS=$((SUCCESS_FILES + SUCCESS_DIRS))

if [ $TOTAL_ITEMS -gt 0 ]; then
    SUCCESS_RATE=$(( SUCCESS_ITEMS * 100 / TOTAL_ITEMS ))
    echo "总成功率: ${SUCCESS_RATE}%"
fi

echo "完成!"
