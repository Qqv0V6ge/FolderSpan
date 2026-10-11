@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem 声明全局变量
set "API_SERVER=#API_SERVER#"
set "USER_AGENT=#USER_AGENT#"
set "LINK_SHARE_SESSION_HEADER=#LINK_SHARE_SESSION_HEADER#"
set "LINK_SHARE_SESSION_TOKEN=#LINK_SHARE_SESSION_TOKEN#"
set "TARGET_DIR=.\#TARGET_DIR#"
set "ROOT_PATH=#ROOT_PATH#"

rem 检查 curl 命令是否可用
where curl >nul 2>&1
if errorlevel 1 (
    echo Error: curl command not found.
    exit /b 1
)

rem 创建目录（如果不存在）
if not exist "%TARGET_DIR%" (
    echo Target directory (%TARGET_DIR%) does not exist. Creating it...
    mkdir "%TARGET_DIR%" >nul 2>&1
    if errorlevel 1 (
        echo Error: failed to create target directory.
        exit /b 1
    )
)

rem 如目标目录非空，提示用户确认是否继续
dir /b /a "%TARGET_DIR%" 2>nul | findstr . >nul
if not errorlevel 1 (
    echo Warning: target directory is not empty. Existing files may be overwritten.
    set "CONFIRM="
    set /p "CONFIRM=Press Enter to continue, or type any text then press Enter to cancel: "
    if defined CONFIRM (
        echo Operation cancelled.
        exit /b 1
    )
    echo Confirmed. Continuing download...
)

rem 初始化计数器
set /a TOTAL_FILES=0
set /a TOTAL_DIRS=0
set /a SUCCESS_FILES=0
set /a FAILED_FILES=0
set /a SUCCESS_DIRS=0
set /a FAILED_DIRS=0

rem 开始递归处理任务
echo Starting recursive download into the current folder...
call :ProcessFilesAndDirectories "%ROOT_PATH%" "%TARGET_DIR%"
if errorlevel 1 exit /b 1

rem 输出统计结果
echo ========== Download Summary ==========
echo Total directories: %TOTAL_DIRS%
echo Created directories: %SUCCESS_DIRS%
echo Failed directories: %FAILED_DIRS%
echo Total files: %TOTAL_FILES%
echo Downloaded files: %SUCCESS_FILES%
echo Failed files: %FAILED_FILES%
echo ======================================

rem 总成功率统计
set /a TOTAL_ITEMS=%TOTAL_FILES% + %TOTAL_DIRS%
set /a SUCCESS_ITEMS=%SUCCESS_FILES% + %SUCCESS_DIRS%
if %TOTAL_ITEMS% gtr 0 (
    set /a SUCCESS_RATE=(%SUCCESS_ITEMS% * 100) / %TOTAL_ITEMS%
    echo Overall success rate: %SUCCESS_RATE%%%
)

echo Done!
exit /b 0

rem 递归处理目录列表并下载文件
:ProcessFilesAndDirectories
set "REMOTE_PATH=%~1"
set "LOCAL_PATH=%~2"
set "LIST_FILE=%TEMP%\share-list-%RANDOM%%RANDOM%.json"

echo Processing path: %REMOTE_PATH% ^> %LOCAL_PATH%

curl -s -f -H "X-API-Request: true" -H "User-Agent: %USER_AGENT%" -H "%LINK_SHARE_SESSION_HEADER%: %LINK_SHARE_SESSION_TOKEN%" "%API_SERVER%%REMOTE_PATH%" -o "%LIST_FILE%"
if errorlevel 1 (
    echo Error: failed to fetch list for path %REMOTE_PATH%
    if exist "%LIST_FILE%" del "%LIST_FILE%" >nul 2>&1
    exit /b 1
)

call :ParseListFile "%LIST_FILE%" "%LOCAL_PATH%"
set "PARSE_EXIT=%ERRORLEVEL%"
if exist "%LIST_FILE%" del "%LIST_FILE%" >nul 2>&1
exit /b %PARSE_EXIT%

rem 解析目录 JSON 列表
:ParseListFile
set "LIST_FILE=%~1"
set "LOCAL_PATH=%~2"
set "JSON="

for /f "usebackq delims=" %%L in ("%LIST_FILE%") do (
    set "JSON=!JSON!%%L"
)

if not defined JSON exit /b 0
if "!JSON!"=="[]" exit /b 0
if "!JSON:~0,1!"=="[" set "JSON=!JSON:~1!"
if "!JSON:~-1!"=="]" set "JSON=!JSON:~0,-1!"

:ParseListLoop
if not defined JSON exit /b 0

for /f "tokens=1* delims=}" %%A in ("!JSON!") do (
    set "CURRENT_OBJECT=%%A}"
    set "JSON=%%B"
)

if defined JSON if "!JSON:~0,1!"=="," set "JSON=!JSON:~1!"

call :ProcessCurrentEntry "%LOCAL_PATH%"
goto :ParseListLoop

rem 处理单个文件或目录对象
:ProcessCurrentEntry
set "LOCAL_PATH=%~1"

set "NAME_PART=!CURRENT_OBJECT:*\"name\":\"=!"
for /f delims^=^"^ tokens^=1 %%A in ("!NAME_PART!") do set "CURRENT_NAME=%%A"

set "PATH_PART=!CURRENT_OBJECT:*\"path\":\"=!"
for /f delims^=^"^ tokens^=1 %%A in ("!PATH_PART!") do set "CURRENT_PATH=%%A"

set "DIR_PART=!CURRENT_OBJECT:*\"isDirectory\":=!"
for /f "tokens=1 delims=,}" %%A in ("!DIR_PART!") do set "CURRENT_IS_DIR=%%A"

set "LOCAL_ITEM_PATH=!LOCAL_PATH!\!CURRENT_NAME!"

if /i "!CURRENT_IS_DIR!"=="true" (
    echo Creating directory: !LOCAL_ITEM_PATH!
    set /a TOTAL_DIRS+=1

    mkdir "!LOCAL_ITEM_PATH!" >nul 2>&1
    if errorlevel 1 (
        echo Error: failed to create directory !LOCAL_ITEM_PATH!
        set /a FAILED_DIRS+=1
        exit /b 0
    )

    set /a SUCCESS_DIRS+=1
    call :ProcessFilesAndDirectories "!CURRENT_PATH!" "!LOCAL_ITEM_PATH!"
    exit /b 0
)

echo Downloading file: !CURRENT_PATH! ^> !LOCAL_ITEM_PATH!
set /a TOTAL_FILES+=1

curl -s -f -H "X-API-Request: true" -H "User-Agent: %USER_AGENT%" -H "%LINK_SHARE_SESSION_HEADER%: %LINK_SHARE_SESSION_TOKEN%" "%API_SERVER%!CURRENT_PATH!" -o "!LOCAL_ITEM_PATH!"
if errorlevel 1 (
    echo Error: failed to download file !CURRENT_PATH!
    set /a FAILED_FILES+=1
    exit /b 0
)

set /a SUCCESS_FILES+=1
exit /b 0
