(function(global) {
  const catalogs = {
    en: {
    common: {
      canceled: "Canceled"
    },
    labels: {
      root: "root directory",
      itemCount: function(count) {
        const safeCount = typeof count === "number" ? count : 0;
        return safeCount + "item";
      },
      unnamed: "Unnamed",
      genericItem: "This item",
      fileFallback: "the file"
    },
    device: {
      empty: "No connected devices yet",
      local: "local machine",
      encrypted: "Encryption",
      server: "server",
      pending: "Pending authorization",
      rejected: "Rejected",
      pendingSuffix: "(pending authorization)",
      rejectedSuffix: "(rejected)"
    },
    actions: {
      more: "More actions",
      download: "Download",
      packDownload: "Package download",
      delete: "Delete",
      editKey: "Modify key"
    },
    confirm: {
      deleteItem: function(name) {
        const target = name || i18n.labels.genericItem;
        return "Confirm to delete" + target + "?";
      }
    },
    fileList: {
      emptySearch: "No matching file or folder found",
      emptyFolder: "The current directory is empty",
      loading: "Loading...",
      loadingFolder: "Load directory...",
      loadFailed: "Unable to load data",
      cryptoError: "Wrong key or data corruption",
      deletePathInvalid: "Delete path is invalid",
      localDeleteUnavailable: "Local deletion capability is not available",
      localDeleteOnly: "Only supports deleting files saved locally by the browser",
      deleteFailed: "Unable to delete local data"
    },
    crypto: {
      enabled: "Already turned on",
      disabled: "Not turned on",
      statusTitle: function(isEnabled) {
        return "Data Encryption (" + (isEnabled ? i18n.crypto.enabled : i18n.crypto.disabled) + "）";
      },
      notSupported: "The current browser does not support encryption",
      keySaved: "Key saved",
      keyPlaceholder: "Enter key",
      promptTitle: function(deviceName) {
        return deviceName ? "visit" + deviceName : "access device";
      },
      promptHint: function(deviceName) {
        if (deviceName) {
          return "Encryption is turned on for this device:" + deviceName + ", please enter the key";
        }
        return "Encryption is turned on for this device, please enter the key";
      },
      promptDefaultHint: "Please enter the key to decrypt device data",
      keyRequired: "Key cannot be empty",
      setLocalKeyFirst: "Please set the local key first",
      retryKey: "Wrong key, please re-enter",
      deviceEncryptedUnsupported: "Encryption is turned on for this device and the current browser cannot decrypt it.",
      decryptSnapshotFailed: "Decryption of directory snapshot failed",
      decryptFailedDetailed: "The device data cannot be decrypted due to incorrect key or data corruption.",
      decryptFailedShort: "Wrong key or data corruption",
      localNotSupported: "Encryption is enabled on this machine, but the current browser does not support encryption"
    },
    download: {
      startFile: function(name) {
        return "Start downloading:" + name;
      },
      startFolder: function(name) {
        return "Start package download:" + name;
      },
      requestFailed: "Request to download failed",
      chunkProcessFailed: "Failed to process file fragments",
      streamSaverRegisterFailed: "Failed to register StreamSaver Service Worker",
      streamSaverInitFailed: "Failed to initialize StreamSaver, fallback to other methods",
      unavailable: "Unable to download: No device connected or selected",
      localReadFailed: "Failed to read local file, use remote download instead",
      initFailedCanceled: "Download initialization failed and canceled",
      initFailed: "Download initialization failed",
      selectFolder: "Please select the folder to be packaged and downloaded",
      folderUnavailable: "Unable to package download folder: No device connected or selected",
      folderBusy: "There is already a folder being packaged and downloaded, please wait.",
      folderWorkerInitFailed: "Unable to initialize folder packaging worker",
      folderWalkFailed: "Failed to traverse folder",
      folderWalkAbort: "Traversal failed",
      connectionUnavailable: "Connection not available",
      dirRequestOverridden: "Directory request has been overridden",
      cleanupOldRequestFailed: "Clean old directory request failed",
      listTimeout: "Directory listing timeout",
      listCanceled: "Directory request canceled",
      folderCancelConnection: "Connection not available, folder download canceled",
      decryptUnsupported: "Unable to decrypt file fragment: Browser does not support encryption",
      decryptUnavailable: "Unable to decrypt",
      decryptChunkFailed: "Failed to decrypt file fragments",
      localFileMissing: "The file does not exist locally and cannot respond to the request.",
      encryptChunkFailed: "Encrypted file fragmentation failed",
      saveCanceled: "User canceled save or could not create file",
      openFileFailed: "Unable to open file",
      writeFailed: "Write failed",
      downloadCanceled: "Download canceled",
      workerFailure: "Download worker exception",
      workerError: "Download worker error:",
      streamSaverWriteFailed: "Writing to StreamSaver failed",
      folderWorkerCreateFailed: "Failed to create folder packaging worker",
      folderWorkerError: "Folder packaging worker exception",
      folderArchiveDone: "Folder package download completed:",
      folderArchived: "Folder is packed:",
      folderArchiveFailed: "Folder packaging failed:",
      folderCompressError: "Folder compression error:",
      snapshotProcessFailed: "Failed to process directory snapshot",
      listRequestCanceled: "Request canceled",
      listRequestNotifyFailed: "Notification directory request failed",
      cancelFolderCompressFailed: "Failed to cancel folder compression",
      streamZipReady: "You can package and save the currently shared content as a ZIP.",
      streamZipHttpsUnavailable: "The current platform does not support downloading ZIP. You can use scripts to download.",
      streamZipNeedHttps: "Downloading the ZIP requires switching to HTTPS.",
      streamZipHttpsDeclined: "has been left in HTTP and can be downloaded using scripts.",
      streamZipPreparing: "Preparing for package download...",
      streamZipLoadingRuntime: "Loading download components...",
      streamZipChooseSaveLocation: "Please select a ZIP save location...",
      streamZipSaveCanceled: "Save canceled",
      streamZipSavePickerUnavailable: "The current browser does not support packaging ZIP after confirming the save. You can use a script to download it.",
      streamZipWalking: function(directory) {
        return directory;
      },
      streamZipReading: function(directory) {
        return directory;
      },
      streamZipDone: function(files, bytes) {
        return "ZIP packaging completed:" + files + "files," + bytes + "Bytes";
      },
      streamZipCanceled: "Package download canceled",
      streamZipFailed: function(message) {
        return message ? ("Package download failed:" + message) : "Package download failed";
      },
      streamZipNoReadableStream: "The current browser does not support streaming reading of files",
      streamZipEmpty: "The current directory has no content to pack"
    },
    upload: {
      deviceMissing: "The current device cannot be recognized and cannot be uploaded.",
      skipUpload: "Skip uploading data to the server and keep the files on this device",
      missingTarget: "Missing upload target, cannot continue",
      uploading: "Uploading",
      encryptSnapshotFailed: "Encrypted directory snapshot failed"
    },
    worker: {
      zipModuleUnavailable: function(detail) {
        return detail ? ("Compression module is not available:" + detail) : "Compression module is not available";
      },
      zipFailed: "Compression failed"
    },
    wakeLock: {
      keepAwake: "Screen always on",
      unsupported: "The current browser does not support always-on screen",
      requestFailed: "Application for always-on screen failed"
    },
    logs: {
      snapshotReceived: "Receive device snapshot:"
    }
  },
    zhHans: {
    common: {
      canceled: "已取消"
    },
    labels: {
      root: "根目录",
      itemCount: function(count) {
        const safeCount = typeof count === "number" ? count : 0;
        return safeCount + "项";
      },
      unnamed: "未命名",
      genericItem: "该项",
      fileFallback: "该文件"
    },
    device: {
      empty: "暂无连接设备",
      local: "本机",
      encrypted: "加密",
      server: "服务器",
      pending: "待授权",
      rejected: "已拒绝",
      pendingSuffix: " (待授权)",
      rejectedSuffix: " (已拒绝)"
    },
    actions: {
      more: "更多操作",
      download: "下载",
      packDownload: "打包下载",
      delete: "删除",
      editKey: "修改密钥"
    },
    confirm: {
      deleteItem: function(name) {
        const target = name || i18n.labels.genericItem;
        return "确定要删除 " + target + " 吗？";
      }
    },
    fileList: {
      emptySearch: "未找到匹配的文件或文件夹",
      emptyFolder: "当前目录为空",
      loading: "加载中...",
      loadingFolder: "加载目录...",
      loadFailed: "无法加载数据",
      cryptoError: "密钥错误或数据损坏",
      deletePathInvalid: "删除路径无效",
      localDeleteUnavailable: "本地删除能力不可用",
      localDeleteOnly: "仅支持删除浏览器本地保存的文件",
      deleteFailed: "未能删除本地数据"
    },
    crypto: {
      enabled: "已开启",
      disabled: "未开启",
      statusTitle: function(isEnabled) {
        return "数据加密（" + (isEnabled ? i18n.crypto.enabled : i18n.crypto.disabled) + "）";
      },
      notSupported: "当前浏览器不支持加密",
      keySaved: "已保存密钥",
      keyPlaceholder: "输入密钥",
      promptTitle: function(deviceName) {
        return deviceName ? "访问 " + deviceName : "访问设备";
      },
      promptHint: function(deviceName) {
        if (deviceName) {
          return "该设备已开启加密：" + deviceName + "，请输入密钥";
        }
        return "该设备已开启加密，请输入密钥";
      },
      promptDefaultHint: "请输入密钥以解密设备数据",
      keyRequired: "密钥不能为空",
      setLocalKeyFirst: "请先设置本机密钥",
      retryKey: "密钥错误，请重新输入",
      deviceEncryptedUnsupported: "该设备已开启加密，当前浏览器无法解密",
      decryptSnapshotFailed: "解密目录快照失败",
      decryptFailedDetailed: "密钥错误或数据损坏，无法解密该设备数据",
      decryptFailedShort: "密钥错误或数据损坏",
      localNotSupported: "本机已启用加密，但当前浏览器不支持加密"
    },
    download: {
      startFile: function(name) {
        return "开始下载：" + name;
      },
      startFolder: function(name) {
        return "开始打包下载：" + name;
      },
      requestFailed: "请求下载失败",
      chunkProcessFailed: "处理文件分片失败",
      streamSaverRegisterFailed: "注册 StreamSaver Service Worker 失败",
      streamSaverInitFailed: "初始化 StreamSaver 失败，回退到其他方式",
      unavailable: "无法下载：未连接或未选择设备",
      localReadFailed: "读取本地文件失败，改用远程下载",
      initFailedCanceled: "下载初始化失败，已取消",
      initFailed: "下载初始化失败",
      selectFolder: "请选择需要打包下载的文件夹",
      folderUnavailable: "无法打包下载文件夹：未连接或未选择设备",
      folderBusy: "已有正在打包下载的文件夹，请稍候",
      folderWorkerInitFailed: "无法初始化文件夹打包 worker",
      folderWalkFailed: "遍历文件夹失败",
      folderWalkAbort: "遍历失败",
      connectionUnavailable: "连接不可用",
      dirRequestOverridden: "目录请求已被覆盖",
      cleanupOldRequestFailed: "清理旧的目录请求失败",
      listTimeout: "列目录超时",
      listCanceled: "目录请求被取消",
      folderCancelConnection: "连接不可用，取消文件夹下载",
      decryptUnsupported: "无法解密文件分片：浏览器不支持加密",
      decryptUnavailable: "无法解密",
      decryptChunkFailed: "解密文件分片失败",
      localFileMissing: "本地无该文件，无法响应请求",
      encryptChunkFailed: "加密文件分片失败",
      saveCanceled: "用户取消保存或无法创建文件",
      openFileFailed: "无法打开文件",
      writeFailed: "写入失败",
      downloadCanceled: "下载已取消",
      workerFailure: "下载 worker 异常",
      workerError: "下载 worker 报错:",
      streamSaverWriteFailed: "写入 StreamSaver 失败",
      folderWorkerCreateFailed: "创建文件夹打包 worker 失败",
      folderWorkerError: "文件夹打包 worker 异常",
      folderArchiveDone: "文件夹打包下载完成:",
      folderArchived: "文件夹已打包:",
      folderArchiveFailed: "文件夹打包失败:",
      folderCompressError: "文件夹压缩错误:",
      snapshotProcessFailed: "处理目录快照失败",
      listRequestCanceled: "请求已取消",
      listRequestNotifyFailed: "通知目录请求失败",
      cancelFolderCompressFailed: "取消文件夹压缩失败",
      streamZipReady: "可以将当前共享内容打包保存为 ZIP。",
      streamZipHttpsUnavailable: "当前平台暂不支持下载 ZIP，可使用脚本下载。",
      streamZipNeedHttps: "下载 ZIP 需要切换到 HTTPS。",
      streamZipHttpsDeclined: "已留在 HTTP，可使用脚本下载。",
      streamZipPreparing: "正在准备打包下载...",
      streamZipLoadingRuntime: "正在加载下载组件...",
      streamZipChooseSaveLocation: "请选择 ZIP 保存位置...",
      streamZipSaveCanceled: "已取消保存",
      streamZipSavePickerUnavailable: "当前浏览器不支持确认保存后打包 ZIP，可使用脚本下载。",
      streamZipWalking: function(directory) {
        return directory;
      },
      streamZipReading: function(directory) {
        return directory;
      },
      streamZipDone: function(files, bytes) {
        return "ZIP 打包完成：" + files + " 个文件，" + bytes + " 字节";
      },
      streamZipCanceled: "已取消打包下载",
      streamZipFailed: function(message) {
        return message ? ("打包下载失败：" + message) : "打包下载失败";
      },
      streamZipNoReadableStream: "当前浏览器不支持流式读取文件",
      streamZipEmpty: "当前目录没有可打包的内容"
    },
    upload: {
      deviceMissing: "未能识别当前设备，无法上传",
      skipUpload: "跳过上传数据到服务器，文件保留在本设备",
      missingTarget: "缺少上传目标，无法继续",
      uploading: "正在上传中",
      encryptSnapshotFailed: "加密目录快照失败"
    },
    worker: {
      zipModuleUnavailable: function(detail) {
        return detail ? ("压缩模块不可用: " + detail) : "压缩模块不可用";
      },
      zipFailed: "压缩失败"
    },
    wakeLock: {
      keepAwake: "屏幕常亮",
      unsupported: "当前浏览器不支持屏幕常亮",
      requestFailed: "屏幕常亮申请失败"
    },
    logs: {
      snapshotReceived: "收到设备快照:"
    }
  }
  };

  function normalizeTag(tag) {
    return String(tag || "").trim().replace(/_/g, "-").toLowerCase();
  }

  function resolveLanguage(tags) {
    const tag = normalizeTag(Array.isArray(tags) ? tags[0] : tags);
    const parts = tag.split("-").filter(Boolean);
    if (parts[0] !== "zh") return "en";
    if (parts.includes("hant") || parts.some((part) => ["tw", "hk", "mo"].includes(part))) {
      return "en";
    }
    if (
      parts.length === 1 ||
      parts.includes("hans") ||
      parts.some((part) => ["cn", "sg"].includes(part))
    ) {
      return "zhHans";
    }
    return "en";
  }

  const preferredLanguages = global.navigator
    ? (global.navigator.languages && global.navigator.languages.length
      ? global.navigator.languages
      : [global.navigator.language])
    : [];
  const language = resolveLanguage(preferredLanguages);
  const i18n = catalogs[language] || catalogs.en;
  const legacyTranslations = {
  "下载 ZIP 需要安全上下文。FolderSpan 使用本机自签名证书，浏览器提示证书风险是预期现象，当前局域网分享不涉及第三方服务。": "Downloading a ZIP requires a security context. FolderSpan uses a native self-signed certificate. The browser prompts that the certificate risk is expected. Currently, LAN sharing does not involve third-party services.",
  "/* 自动生成的 Material 3 主题变量，与 Compose 主题保持同步 */": "/* Automatically generated Material 3 theme variables, synchronized with the Compose theme */",
  "$directoryCount 个文件夹 · $fileCount 个文件": "$directoryCount folders · $fileCount files",
  "来自${getSocketDevice().name}的分享": "Share from ${getSocketDevice().name}",
  "当前浏览器不支持确认保存后打包 ZIP，可使用脚本下载。": "The current browser does not support packaging ZIP after confirming the save. You can use a script to download it.",
  "请输入设备所有者提供的共享密码。密码不会被保存。": "Please enter the shared password provided by the device owner. Passwords will not be saved.",
  "当前平台暂不支持下载 ZIP，可使用脚本下载。": "The current platform does not support downloading ZIP. You can use scripts to download.",
  "$directoryCount 个文件夹": "$directoryCount folders",
  "可以将当前共享内容打包保存为 ZIP。": "You can package and save the currently shared content as a ZIP.",
  " 已存在。\n\n确定覆盖，取消跳过。": "already exists.\n\nOK to overwrite, cancel to skip.",
  "对方正在准备文件，请稍候片刻...": "The other party is preparing documents, please wait a moment...",
  "输入关键词后按回车即可过滤文件列表": "Enter keywords and press Enter to filter the file list",
  "每次只能上传一个文件或一个文件夹": "Only one file or folder can be uploaded at a time",
  "${files.size} 项": "${files.size} items",
  "请选择 ZIP 保存位置...": "Please select a ZIP save location...",
  "$fileCount 个文件": "$fileCount files",
  "当前平台暂不支持下载 ZIP": "The current platform does not currently support downloading ZIP",
  "当前浏览器不支持流式读取文件": "The current browser does not support streaming reading of files",
  "当前设备的访问请求已被拒绝。": "The access request for the current device has been denied.",
  "${file.size}项": "${file.size} item",
  "刷新页面即可查看最新文件。": "Refresh the page to view the latest files.",
  "上传中断后需要重新上传。": "After the upload is interrupted, it needs to be uploaded again.",
  "下载 ZIP 组件不可用": "Download ZIP component not available",
  "当前浏览器不支持屏幕常亮": "The current browser does not support always-on screen",
  "当前目录没有可打包的内容": "The current directory has no content to pack",
  "未找到匹配的文件或文件夹": "No matching file or folder found",
  "上传进行中，请等待完成": "Upload in progress, please wait for completion",
  "关闭 ZIP 下载弹窗": "Close ZIP download pop-up window",
  "正在准备打包下载...": "Preparing for package download...",
  "此内容需要密码才能访问": "This content requires a password to access",
  "每次只能上传一个文件夹": "Only one folder can be uploaded at a time",
  "需要切换到 HTTPS": "Need to switch to HTTPS",
  "共享文件和文件夹列表": "Shared file and folder lists",
  "刷新页面查看最新文件": "Refresh the page to view the latest files",
  "浏览器 ZIP 下载": "Browser ZIP Download",
  "继续使用 HTTPS": "Continue to use HTTPS",
  "上传权限请求已提交": "Upload permission request submitted",
  "未完成文件清理失败": "Unfinished file cleanup failed",
  "松开上传到当前目录": "Release and upload to current directory",
  "网络异常，上传失败": "Network abnormality, upload failed",
  "，可使用脚本下载。": ", which can be downloaded using a script.",
  "ZIP 打包完成": "ZIP packaging completed",
  "上传文件和文件夹": "Upload files and folders",
  "上传权限请求失败": "Upload permission request failed",
  "关闭上传进度弹窗": "Close the upload progress pop-up window",
  "关闭批量下载弹窗": "Close batch download pop-up window",
  "屏幕常亮申请失败": "Application for always-on screen failed",
  "打包保存 ZIP": "Package and save ZIP",
  "无法创建保存文件": "Unable to create save file",
  "没有可上传的项目": "No items to upload",
  "没有可显示的项目": "No items to display",
  "读取拖拽项目失败": "Failed to read drag and drop items",
  "创建文件夹失败": "Failed to create folder",
  "已取消打包下载": "Package download canceled",
  "父文件夹已跳过": "Parent folder skipped",
  "留在 HTTP": "stay in HTTP",
  "请输入访问密码": "Please enter access password",
  "跳转到主要内容": "Skip to main content",
  "上传检查失败": "Upload check failed",
  "上传路径无效": "Invalid upload path",
  "下载 ZIP": "Download ZIP",
  "打包下载失败": "Package download failed",
  "暂无可用脚本": "No script available yet",
  "正在取消上传": "Canceling upload",
  "用户选择跳过": "User chooses to skip",
  "请求上传权限": "Request upload permission",
  "上传已取消": "Upload canceled",
  "已取消保存": "Save canceled",
  "文件准备中": "Documents in preparation",
  "访问被拒绝": "access denied",
  "选择文件夹": "Select folder",
  "预计时间 ": "Estimated time",
  "上传失败": "Upload failed",
  "上传完成": "Upload completed",
  "上传进度": "Upload progress",
  "下载脚本": "Download script",
  "共享内容": "Share content",
  "刷新页面": "refresh page",
  "取消上传": "Cancel upload",
  "取消打包": "Unpack",
  "屏幕常亮": "Screen always on",
  "批量下载": "Batch download",
  "搜索文件": "Search files",
  "正在取消": "Canceling",
  "访问密码": "access password",
  "访问验证": "Access verification",
  "选择文件": "Select file",
  "页面路径": "Page path",
  "首页": "Home",
  "验证访问": "Verify access",
  " 剩余": "Remaining",
  " 完成": "Complete",
  " 总数": "total",
  "上传中": "Uploading",
  "创建中": "Creating",
  "已取消": "Canceled",
  "已跳过": "skipped",
  "待上传": "To be uploaded",
  "待处理": "Pending",
  "文件夹": "folder",
  "用时 ": "time",
  "速度 ": "speed",
  "上传": "upload",
  "关闭": "close",
  "取消": "Cancel",
  "失败": "failed",
  "完成": "Complete"
};
  const replacementEntries = Object.entries(legacyTranslations)
    .sort((left, right) => right[0].length - left[0].length);
  const hanCharacterPattern = /[\u3400-\u9fff]/;

  function translateDynamicLegacyValue(value) {
    let match = /^来自(.+)的分享$/.exec(value);
    if (match) return "Share from " + match[1];

    match = /^(\d+) 个文件夹 · (\d+) 个文件$/.exec(value);
    if (match) {
      const folderLabel = match[1] === "1" ? " folder" : " folders";
      const fileLabel = match[2] === "1" ? " file" : " files";
      return match[1] + folderLabel + " · " + match[2] + fileLabel;
    }

    match = /^(\d+) 个文件夹$/.exec(value);
    if (match) return match[1] + (match[1] === "1" ? " folder" : " folders");

    match = /^(\d+) 个文件$/.exec(value);
    if (match) return match[1] + (match[1] === "1" ? " file" : " files");

    match = /^(\d+)\s*项$/.exec(value);
    if (match) return match[1] + (match[1] === "1" ? " item" : " items");

    return value;
  }

  function translateUnknownLegacyStatus(value) {
    if (!hanCharacterPattern.test(value)) return value;
    if (/完成|成功|已保存|已创建|已删除|已连接/.test(value) && !/失败|未完成|不完整|错误|异常/.test(value)) {
      return "Completed";
    }
    if (/正在|开始|等待|准备中|处理中|加载中/.test(value) && !/失败|错误|异常|超时/.test(value)) {
      return "Processing…";
    }
    if (/权限|无权|未授权|拒绝访问|不允许访问/.test(value)) return "Permission denied";
    if (/超时/.test(value)) return "The request timed out";
    if (/取消|已停止|终止/.test(value)) return "Canceled";
    if (/不存在|未找到|找不到/.test(value)) return "The requested item was not found";
    if (/已存在|重复|冲突/.test(value)) return "The destination already exists";
    if (/不支持|不可用|无法使用|未启用/.test(value)) return "This operation is not supported";
    if (/无效|不匹配|不能为空|为空|缺少|超过限制|过大/.test(value)) return "The request is invalid";
    if (/连接失败|无法连接|网络异常|网络错误/.test(value)) return "Connection failed";
    if (/失败|错误|异常|无法/.test(value)) return "Operation failed";
    return "Operation failed";
  }

  i18n.language = language;
  i18n.resolveLanguage = resolveLanguage;
  i18n.t = function(value) {
    if (language === "zhHans" || value == null) return value;
    let translated = String(value);
    const dynamicTranslation = translateDynamicLegacyValue(translated);
    if (dynamicTranslation !== translated) return dynamicTranslation;
    replacementEntries.forEach(([source, target]) => {
      if (translated.includes(source)) translated = translated.split(source).join(target);
    });
    return translateUnknownLegacyStatus(translated);
  };

  i18n.applyDocument = function(root) {
    if (!root || language === "zhHans") return;
    const excluded = "pre, code, script, style, .file-card__title, .breadcrumb-item, .upload-task__title";
    const translateElement = function(element) {
      if (!element || (element.closest && element.closest(excluded))) return;
      ["placeholder", "aria-label", "title"].forEach((name) => {
        if (element.hasAttribute && element.hasAttribute(name)) {
          element.setAttribute(name, i18n.t(element.getAttribute(name)));
        }
      });
    };
    if (root.nodeType === 1) translateElement(root);
    const walker = document.createTreeWalker(
      root,
      NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT
    );
    let node = walker.currentNode;
    while (node) {
      if (node.nodeType === Node.TEXT_NODE) {
        const parent = node.parentElement;
        if (parent && !parent.closest(excluded)) node.nodeValue = i18n.t(node.nodeValue);
      } else {
        translateElement(node);
      }
      node = walker.nextNode();
    }
    document.documentElement.lang = "en";
    document.title = i18n.t(document.title);
  };

  global.I18N = i18n;

  if (typeof document !== "undefined") {
    const apply = function() {
      i18n.applyDocument(document.body || document.documentElement);
      if (language === "en" && typeof MutationObserver !== "undefined") {
        new MutationObserver((mutations) => {
          mutations.forEach((mutation) => {
            mutation.addedNodes.forEach((node) => i18n.applyDocument(node));
          });
        }).observe(document.body, { childList: true, subtree: true });
      }
    };
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", apply, { once: true });
    } else {
      apply();
    }
    if (language === "en") {
      const originalAlert = global.alert && global.alert.bind(global);
      const originalConfirm = global.confirm && global.confirm.bind(global);
      if (originalAlert) global.alert = (message) => originalAlert(i18n.t(message));
      if (originalConfirm) global.confirm = (message) => originalConfirm(i18n.t(message));
    }
  }
})(typeof self !== "undefined" ? self : window);
