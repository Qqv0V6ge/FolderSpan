# 声明全局变量
$API_SERVER = "#API_SERVER#"
$USER_AGENT = "#USER_AGENT#"
$LINK_SHARE_SESSION_HEADER = "#LINK_SHARE_SESSION_HEADER#"
$LINK_SHARE_SESSION_TOKEN = "#LINK_SHARE_SESSION_TOKEN#"
$TARGET_DIR = ".\#TARGET_DIR#"
$ROOT_PATH = "#ROOT_PATH#"

# 创建目录（如果不存在）
if (-not (Test-Path -Path $TARGET_DIR)) {
    Write-Host "目标目录 ($TARGET_DIR) 不存在，将自动创建..."
    New-Item -Path $TARGET_DIR -ItemType Directory | Out-Null
}

# 如目标目录非空，提示用户确认是否继续
if (Get-ChildItem -Path $TARGET_DIR -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name }) {
    Write-Warning "警告: 目录非空，继续操作可能覆盖文件。"
    Write-Host "按回车键继续，按任何其他键取消..."

    $key = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
    if ($key.VirtualKeyCode -ne 13) {
        Write-Host "操作已取消。"
        exit 1
    }

    Write-Host "用户已确认，将覆盖文件继续下载..."
}

# 初始化计数器
$Global:TOTAL_FILES = 0
$Global:TOTAL_DIRS = 0
$Global:SUCCESS_FILES = 0
$Global:FAILED_FILES = 0
$Global:SUCCESS_DIRS = 0
$Global:FAILED_DIRS = 0

# 定义处理函数
function Process-FilesAndDirectories {
    param (
        [string]$RemotePath,
        [string]$LocalPath
    )

    Write-Host "处理路径: $RemotePath -> $LocalPath"

    # 使用Invoke-WebRequest发送请求
    try {
        $response = Invoke-WebRequest -Uri "${API_SERVER}$RemotePath" `
                                      -Headers @{"X-API-Request"="true"; "User-Agent"="$USER_AGENT"; "$LINK_SHARE_SESSION_HEADER"="$LINK_SHARE_SESSION_TOKEN"} `
                                      -UseBasicParsing | Select-Object -ExpandProperty Content | ConvertFrom-Json
    }
    catch {
        Write-Error "错误: 无法解析路径 $RemotePath 的响应"
        return
    }

    foreach ($item in $response) {
        if($item -eq $null){ continue }

        $name = $item.name
        $is_dir = $item.isDirectory
        $item_path = $item.path
        $local_item_path = Join-Path $LocalPath $name

        if ($is_dir -eq $true) {
            Write-Host "创建目录: $local_item_path"
            $Global:TOTAL_DIRS++

            try {
                New-Item -Path $local_item_path -ItemType Directory -Force | Out-Null
                $Global:SUCCESS_DIRS++
            }
            catch {
                Write-Error "错误: 无法创建目录 $local_item_path"
                $Global:FAILED_DIRS++
                continue
            }

            # 递归处理目录
            Process-FilesAndDirectories -RemotePath $item_path -LocalPath $local_item_path

        } else {
            Write-Host "下载文件: $item_path -> $local_item_path"
            $Global:TOTAL_FILES++

            try {
                Invoke-WebRequest -Uri "${API_SERVER}$item_path" `
                                  -Headers @{"X-API-Request"="true"; "User-Agent"="$USER_AGENT"; "$LINK_SHARE_SESSION_HEADER"="$LINK_SHARE_SESSION_TOKEN"} `
                                  -OutFile $local_item_path -ErrorAction Stop -UseBasicParsing
                $Global:SUCCESS_FILES++
            }
            catch {
                Write-Error "错误: 无法下载文件 $item_path"
                $Global:FAILED_FILES++
            }
        }
    }
}

# 开始递归处理任务
Write-Host "开始下载文件和创建目录到当前文件夹..."
Process-FilesAndDirectories -RemotePath $ROOT_PATH -LocalPath $TARGET_DIR

# 输出统计结果
Write-Host "========== 下载统计 =========="
Write-Host "总文件夹数: $TOTAL_DIRS"
Write-Host "成功创建的文件夹: $SUCCESS_DIRS"
Write-Host "创建失败的文件夹: $FAILED_DIRS"
Write-Host "总文件数: $TOTAL_FILES"
Write-Host "成功下载的文件: $SUCCESS_FILES"
Write-Host "下载失败的文件: $FAILED_FILES"
Write-Host "============================="

# 总成功率统计
$TOTAL_ITEMS = $TOTAL_FILES + $TOTAL_DIRS
$SUCCESS_ITEMS = $SUCCESS_FILES + $SUCCESS_DIRS

if ($TOTAL_ITEMS -gt 0) {
    $SUCCESS_RATE = [Math]::Round(($SUCCESS_ITEMS / $TOTAL_ITEMS) * 100, 2)
    Write-Host "总成功率: $SUCCESS_RATE%"
}

Write-Host "完成!"
