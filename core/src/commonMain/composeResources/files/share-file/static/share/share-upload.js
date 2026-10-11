document.addEventListener("DOMContentLoaded", function() {
  initUploadQueue();
});

const UPLOAD_CHUNK_BYTES = 8 * 1024 * 1024;

function initUploadQueue() {
  const uploadSection = document.getElementById("uploadSection");
  const dropOverlay = document.getElementById("uploadPageDropOverlay");
  const fileInput = document.getElementById("uploadFileInput");
  const directoryInput = document.getElementById("uploadDirectoryInput");
  const fileButton = document.getElementById("selectUploadFiles");
  const directoryButton = document.getElementById("selectUploadDirectory");
  const queueElement = document.getElementById("uploadQueue");
  const keepAwakeToggle = document.getElementById("uploadKeepAwakeToggle");
  const uploadOverlay = document.getElementById("uploadProgressModal");
  const uploadDialog = uploadOverlay ? uploadOverlay.querySelector(".modal") : null;
  const closeUploadBtn = document.getElementById("closeUploadProgressModal");
  const cancelUploadBtn = document.getElementById("cancelUploadProgress");
  const cancelUploadLabel = document.getElementById("cancelUploadProgressLabel");
  const refreshAfterUploadBtn = document.getElementById("refreshAfterUpload");
  const uploadCompletionPanel = document.getElementById("uploadCompletionPanel");

  if (
    !uploadSection ||
    !fileInput ||
    !directoryInput ||
    !fileButton ||
    !directoryButton ||
    !queueElement ||
    !uploadOverlay ||
    !uploadDialog ||
    !closeUploadBtn ||
    !cancelUploadBtn ||
    !cancelUploadLabel ||
    !refreshAfterUploadBtn ||
    !uploadCompletionPanel
  ) {
    return;
  }

  const queue = [];
  let processing = false;
  let nextTaskId = 1;
  let completedUploads = 0;
  let dragDepth = 0;
  let statsTimer = 0;
  let cancelRequested = false;
  let activeUploadXhr = null;
  let activeAbortController = null;
  let uploadPreviouslyFocusedElement = null;
  const focusableSelectors = 'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])';

  const config = readLinkSharePageConfig();

  const isFinalStatus = (task) => ["done", "skipped", "error", "canceled"].includes(task.status);
  const isProcessedStatus = (task) => ["done", "skipped"].includes(task.status);
  const isCanceledError = (error) => error && error.name === "FolderSpanUploadCanceled";

  const createCanceledError = () => {
    const error = new Error("上传已取消");
    error.name = "FolderSpanUploadCanceled";
    return error;
  };

  const throwIfUploadCanceled = () => {
    if (cancelRequested) {
      throw createCanceledError();
    }
  };

  const isQueueBusy = () => processing || queue.some((task) => !isFinalStatus(task));

  const shouldWarnBeforeLeave = () => queue.some((task) => !isFinalStatus(task));

  window.addEventListener("beforeunload", (event) => {
    if (!shouldWarnBeforeLeave()) return;
    event.preventDefault();
    event.returnValue = "上传中断后需要重新上传。";
    return event.returnValue;
  });

  const setUploadControlsDisabled = (disabled) => {
    fileButton.disabled = disabled;
    directoryButton.disabled = disabled;
  };

  const setUploadCompletionVisible = (visible) => {
    uploadCompletionPanel.classList.toggle("hidden", !visible);
    uploadCompletionPanel.setAttribute("aria-hidden", String(!visible));
    refreshAfterUploadBtn.classList.toggle("hidden", !visible);
  };

  const setUploadCanceling = (canceling) => {
    cancelUploadBtn.disabled = canceling || !processing;
    cancelUploadLabel.textContent = canceling ? "正在取消" : "取消上传";
  };

  const setUploadDialogBusy = (busy) => {
    closeUploadBtn.disabled = busy;
    refreshAfterUploadBtn.disabled = busy;
    cancelUploadBtn.classList.toggle("hidden", !busy);
    cancelUploadBtn.disabled = !busy;
    if (!busy) {
      setUploadCanceling(false);
    }
  };

  const trapUploadFocus = (event) => {
    if (event.key !== "Tab") return;
    const focusableElements = uploadDialog.querySelectorAll(focusableSelectors);
    if (focusableElements.length === 0) return;

    const firstElement = focusableElements[0];
    const lastElement = focusableElements[focusableElements.length - 1];
    if (event.shiftKey && document.activeElement === firstElement) {
      event.preventDefault();
      lastElement.focus();
    } else if (!event.shiftKey && document.activeElement === lastElement) {
      event.preventDefault();
      firstElement.focus();
    }
  };

  const showUploadModal = () => {
    const wasHidden = uploadOverlay.classList.contains("hidden");
    if (wasHidden) {
      uploadPreviouslyFocusedElement = document.activeElement;
    }
    uploadOverlay.classList.remove("hidden");
    uploadOverlay.setAttribute("aria-hidden", "false");
    if (wasHidden) {
      uploadDialog.focus();
      uploadDialog.addEventListener("keydown", trapUploadFocus);
      (keepAwakeToggle || closeUploadBtn || uploadDialog).focus();
    }
  };

  const closeUploadModal = (restoreFocus = true) => {
    if (processing) return;
    uploadOverlay.classList.add("hidden");
    uploadOverlay.setAttribute("aria-hidden", "true");
    uploadDialog.removeEventListener("keydown", trapUploadFocus);

    if (restoreFocus && uploadPreviouslyFocusedElement instanceof HTMLElement) {
      uploadPreviouslyFocusedElement.focus();
    }
  };

  const nowMs = () => (typeof performance !== "undefined" && typeof performance.now === "function" ? performance.now() : Date.now());

  const escapeHtml = (value) => String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");

  const formatSize = (bytes) => {
    if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
    const units = ["B", "KB", "MB", "GB", "TB"];
    let size = bytes;
    let index = 0;
    while (size >= 1024 && index < units.length - 1) {
      size /= 1024;
      index += 1;
    }
    return (index === 0 ? size.toFixed(0) : size.toFixed(1)) + " " + units[index];
  };

  const formatSpeed = (bytesPerSecond) => {
    if (!Number.isFinite(bytesPerSecond) || bytesPerSecond <= 0) return "0 B/s";
    const units = ["B/s", "KB/s", "MB/s", "GB/s", "TB/s"];
    let speed = bytesPerSecond;
    let index = 0;
    while (speed >= 1024 && index < units.length - 1) {
      speed /= 1024;
      index += 1;
    }
    return (index === 0 ? speed.toFixed(0) : speed.toFixed(1)) + " " + units[index];
  };

  const formatDuration = (ms) => {
    if (!Number.isFinite(ms) || ms < 0) return "--:--";
    const totalSeconds = Math.max(0, Math.floor(ms / 1000));
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) {
      return String(hours) + ":" + String(minutes).padStart(2, "0") + ":" + String(seconds).padStart(2, "0");
    }
    return String(minutes).padStart(2, "0") + ":" + String(seconds).padStart(2, "0");
  };

  const getTaskTotalBytes = (task) => {
    if (Number.isFinite(task.totalBytes)) return Math.max(0, task.totalBytes);
    return Math.max(0, task.size || 0);
  };

  const getTaskUploadedBytes = (task) => {
    if (Number.isFinite(task.uploadedBytes)) {
      return Math.max(0, task.uploadedBytes);
    }
    if (task.status === "done") {
      return getTaskTotalBytes(task);
    }
    return 0;
  };

  const getTaskTotalItems = (task) => {
    if (task.type === "folder") {
      return Array.isArray(task.children) ? task.children.length : 0;
    }
    return 1;
  };

  const getTaskCompletedItems = (task) => {
    if (task.type === "folder") {
      return (task.children || []).filter(isProcessedStatus).length;
    }
    return isProcessedStatus(task) ? 1 : 0;
  };

  const buildTaskStats = (task) => {
    const totalItems = getTaskTotalItems(task);
    const completedItems = Math.min(getTaskCompletedItems(task), totalItems);
    return {
      totalItems,
      completedItems,
      remainingItems: Math.max(totalItems - completedItems, 0)
    };
  };

  const buildTaskTelemetry = (task) => {
    const totalBytes = getTaskTotalBytes(task);
    const uploadedBytes = Math.min(getTaskUploadedBytes(task), totalBytes || getTaskUploadedBytes(task));
    const anchorTime = task.finishedAt || nowMs();
    const startAt = task.startedAt || anchorTime;
    const elapsedMs = Math.max(0, anchorTime - startAt);
    const stats = buildTaskStats(task);
    const speed = elapsedMs > 0 ? (uploadedBytes * 1000) / elapsedMs : 0;
    const remainingBytes = Math.max(totalBytes - uploadedBytes, 0);
    let remainingMs = speed > 0 ? (remainingBytes * 1000) / speed : null;
    if (isFinalStatus(task)) {
      remainingMs = 0;
    } else if (remainingMs == null && elapsedMs > 0 && stats.completedItems > 0) {
      remainingMs = (Math.max(stats.totalItems - stats.completedItems, 0) * elapsedMs) / stats.completedItems;
    }
    return {
      elapsedText: formatDuration(elapsedMs),
      etaText: remainingMs == null ? "--:--" : formatDuration(remainingMs),
      speedText: formatSpeed(speed),
      uploadedBytes,
      totalBytes
    };
  };

  const showSnackbar = (message) => {
    let snackbar = document.getElementById("uploadSnackbar");
    if (!snackbar) {
      snackbar = document.createElement("div");
      snackbar.id = "uploadSnackbar";
      snackbar.className = "snackbar";
      snackbar.innerHTML = '<span class="snackbar__text"></span>';
      document.body.appendChild(snackbar);
    }

    const text = snackbar.querySelector(".snackbar__text");
    if (text) text.textContent = message;
    snackbar.classList.add("snackbar--visible");
    window.clearTimeout(snackbar._hideTimer);
    snackbar._hideTimer = window.setTimeout(() => {
      snackbar.classList.remove("snackbar--visible");
    }, 3200);
  };

  const i18n = window.I18N || {};
  const wakeLockController = window.FolderSpanWakeLock
    ? window.FolderSpanWakeLock.createController({
      checkbox: keepAwakeToggle,
      messages: i18n.wakeLock || {},
      notify: showSnackbar
    })
    : {
      begin: function() {},
      end: function() {},
      setEnabled: function() {},
      isEnabled: function() { return false; }
    };

  const hasDraggedFiles = (dataTransfer) => {
    if (!dataTransfer) return false;
    const types = Array.from(dataTransfer.types || []);
    return types.includes("Files") || types.includes("application/x-moz-file");
  };

  const setPageDropActive = (active) => {
    if (!dropOverlay) return;
    dropOverlay.classList.toggle("hidden", !active);
    dropOverlay.setAttribute("aria-hidden", active ? "false" : "true");
  };

  const normalizeRelativePath = (path) => {
    const value = String(path || "").replace(/\\/g, "/").replace(/^\/+/, "");
    const segments = value.split("/").filter((segment) => segment.length > 0);
    if (segments.length === 0) return "";
    if (segments.some((segment) => segment === "." || segment === ".." || segment.includes("\0"))) {
      return "";
    }
    return segments.join("/");
  };

  const buildApiUrl = (endpoint, task, overwrite) => {
    const url = new URL(endpoint, config.httpBaseUrl);
    const params = url.searchParams;
    params.set("target", window.location.pathname || "/");
    params.set("relativePath", task.relativePath);
    params.set("type", task.type);
    if (typeof overwrite === "boolean") {
      params.set("overwrite", overwrite ? "true" : "false");
    }
    if (task.type === "file") {
      params.set("size", String(task.size || 0));
    }

    return url.toString();
  };

  const abortableFetch = async (url, options) => {
    throwIfUploadCanceled();
    const controller = new AbortController();
    activeAbortController = controller;
    try {
      return await fetch(url, Object.assign({}, options, { signal: controller.signal }));
    } catch (error) {
      if (cancelRequested && error && error.name === "AbortError") {
        throw createCanceledError();
      }
      throw error;
    } finally {
      if (activeAbortController === controller) {
        activeAbortController = null;
      }
    }
  };

  const statusText = (task) => {
    switch (task.status) {
      case "running":
        return task.type === "directory" ? "创建中" : "上传中";
      case "done":
        return "完成";
      case "skipped":
        return "已跳过";
      case "error":
        return "失败";
      case "canceled":
        return "已取消";
      default:
        return task.type === "folder" ? "待上传" : "待处理";
    }
  };

  const markTaskCanceled = (task) => {
    if (!task || isFinalStatus(task)) return;
    task.status = "canceled";
    task.message = "已取消";
    task.finishedAt = nowMs();
    if (task.type === "folder" && Array.isArray(task.children)) {
      task.children.forEach(markTaskCanceled);
    }
  };

  const renderQueue = () => {
    if (queue.length === 0) {
      queueElement.classList.add("hidden");
      queueElement.innerHTML = "";
      return;
    }

    queueElement.classList.remove("hidden");
    queueElement.innerHTML = queue.map((task) => {
      const classes = ["upload-task", "upload-task--" + task.status].join(" ");
      const meta = task.message || (task.type === "folder" ? "上传" : task.type === "directory" ? "文件夹" : formatSize(task.size));
      const width = task.status === "done" ? 100 : Math.max(0, Math.min(100, task.progress || 0));
      const telemetry = buildTaskTelemetry(task);
      const stats = buildTaskStats(task);
      return [
        '<div class="' + classes + '">',
        '<div class="upload-task__main">',
        '<span class="upload-task__title">' + escapeHtml(task.relativePath) + '</span>',
        '<span class="upload-task__meta">' + escapeHtml(meta) + '</span>',
        '</div>',
        '<div class="upload-task__stats">',
        '<span class="upload-task__status">' + escapeHtml(statusText(task)) + '</span>',
        '<span>' + escapeHtml(stats.totalItems + " 总数") + '</span>',
        '<span>' + escapeHtml(stats.completedItems + " 完成") + '</span>',
        '<span>' + escapeHtml(stats.remainingItems + " 剩余") + '</span>',
        '<span>' + escapeHtml("用时 " + telemetry.elapsedText) + '</span>',
        '<span>' + escapeHtml("预计时间 " + telemetry.etaText) + '</span>',
        '<span>' + escapeHtml("速度 " + telemetry.speedText) + '</span>',
        '</div>',
        '<div class="upload-task__progress" aria-hidden="true">',
        '<div class="upload-task__progress-bar" style="width: ' + width + '%"></div>',
        '</div>',
        '</div>'
      ].join("");
    }).join("");
  };

  const startStatsTimer = () => {
    if (statsTimer) return;
    statsTimer = window.setInterval(() => {
      if (isQueueBusy()) {
        renderQueue();
      } else {
        stopStatsTimer();
      }
    }, 1000);
  };

  const stopStatsTimer = () => {
    if (!statsTimer) return;
    window.clearInterval(statsTimer);
    statsTimer = 0;
  };

  const makeDirectoryTask = (relativePath) => ({
    id: nextTaskId++,
    type: "directory",
    relativePath,
    size: 0,
    totalBytes: 0,
    uploadedBytes: 0,
    status: "pending",
    progress: 0,
    message: "",
    startedAt: 0,
    finishedAt: 0
  });

  const makeFileTask = (file, relativePath) => ({
    id: nextTaskId++,
    type: "file",
    file,
    relativePath,
    size: file.size || 0,
    totalBytes: file.size || 0,
    uploadedBytes: 0,
    status: "pending",
    progress: 0,
    message: "",
    startedAt: 0,
    finishedAt: 0
  });

  const makeFolderTask = (relativePath, children) => ({
    id: nextTaskId++,
    type: "folder",
    relativePath,
    children,
    size: children
      .filter((task) => task.type === "file")
      .reduce((total, task) => total + (task.size || 0), 0),
    totalBytes: children
      .filter((task) => task.type === "file")
      .reduce((total, task) => total + (task.size || 0), 0),
    uploadedBytes: 0,
    status: "pending",
    progress: 0,
    message: "上传",
    startedAt: 0,
    finishedAt: 0
  });

  const addParentDirectories = (relativePath, directoryMap) => {
    const segments = relativePath.split("/");
    if (segments.length <= 1) return;
    let current = "";
    for (let index = 0; index < segments.length - 1; index += 1) {
      current = current ? current + "/" + segments[index] : segments[index];
      if (current && !directoryMap.has(current)) {
        directoryMap.set(current, makeDirectoryTask(current));
      }
    }
  };

  const enqueueTasks = (tasks) => {
    if (tasks.length === 0) {
      showSnackbar("没有可上传的项目");
      return;
    }
    if (isQueueBusy()) {
      showSnackbar("上传进行中，请等待完成");
      return;
    }

    queue.length = 0;
    completedUploads = 0;
    setUploadCompletionVisible(false);
    queue.push(...tasks);
    setUploadControlsDisabled(true);
    renderQueue();
    startStatsTimer();
    showUploadModal();
    processQueue();
  };

  const enqueueSingleFile = (files) => {
    const selectedFiles = Array.from(files || []);
    if (selectedFiles.length === 0) {
      showSnackbar("没有可上传的项目");
      return;
    }
    if (selectedFiles.length !== 1) {
      showSnackbar("每次只能上传一个文件或一个文件夹");
      return;
    }

    const file = selectedFiles[0];
    const relativePath = normalizeRelativePath(file.name);
    if (!relativePath) {
      showSnackbar("上传路径无效");
      return;
    }
    enqueueTasks([makeFileTask(file, relativePath)]);
  };

  const buildFolderTaskFromFiles = (files) => {
    const directoryMap = new Map();
    const fileTasks = [];
    const rootNames = new Set();

    Array.from(files || []).forEach((file) => {
      const rawPath = file.webkitRelativePath || file.name;
      const relativePath = normalizeRelativePath(rawPath);
      if (!relativePath) return;
      const rootName = relativePath.split("/")[0];
      if (rootName) rootNames.add(rootName);
      addParentDirectories(relativePath, directoryMap);
      fileTasks.push(makeFileTask(file, relativePath));
    });

    if (rootNames.size !== 1) {
      return null;
    }

    const directoryTasks = Array.from(directoryMap.values())
      .sort((a, b) => a.relativePath.localeCompare(b.relativePath));
    const children = directoryTasks.concat(fileTasks);
    if (children.length === 0) {
      return null;
    }
    return makeFolderTask(Array.from(rootNames)[0], children);
  };

  const enqueueFolderFiles = (files) => {
    const selectedFiles = Array.from(files || []);
    if (selectedFiles.length === 0) {
      showSnackbar("没有可上传的项目");
      return;
    }

    const folderTask = buildFolderTaskFromFiles(selectedFiles);
    if (!folderTask) {
      showSnackbar("每次只能上传一个文件夹");
      return;
    }
    enqueueTasks([folderTask]);
  };

  const readDirectoryEntries = (reader) => new Promise((resolve, reject) => {
    const entries = [];
    const readBatch = () => {
      reader.readEntries((batch) => {
        if (!batch.length) {
          resolve(entries);
          return;
        }
        entries.push(...batch);
        readBatch();
      }, reject);
    };
    readBatch();
  });

  const traverseEntry = async (entry, parentPath, tasks) => {
    const relativePath = normalizeRelativePath(parentPath ? parentPath + "/" + entry.name : entry.name);
    if (!relativePath) return;

    if (entry.isDirectory) {
      tasks.push(makeDirectoryTask(relativePath));
      const children = await readDirectoryEntries(entry.createReader());
      for (const child of children) {
        await traverseEntry(child, relativePath, tasks);
      }
      return;
    }

    if (entry.isFile) {
      const file = await new Promise((resolve, reject) => {
        entry.file(resolve, reject);
      });
      tasks.push(makeFileTask(file, relativePath));
    }
  };

  const enqueueDroppedItems = async (dataTransfer) => {
    const tasks = [];
    const items = Array.from(dataTransfer.items || []);
    const entries = items
      .map((item) => typeof item.webkitGetAsEntry === "function" ? item.webkitGetAsEntry() : null)
      .filter(Boolean);

    if (entries.length > 0) {
      if (entries.length !== 1) {
        showSnackbar("每次只能上传一个文件或一个文件夹");
        return;
      }

      const entry = entries[0];
      if (entry.isDirectory) {
        await traverseEntry(entry, "", tasks);
        if (tasks.length === 0) {
          showSnackbar("没有可上传的项目");
          return;
        }
        enqueueTasks([makeFolderTask(normalizeRelativePath(entry.name), tasks)]);
        return;
      }

      if (entry.isFile) {
        const file = await new Promise((resolve, reject) => {
          entry.file(resolve, reject);
        });
        const relativePath = normalizeRelativePath(file.name || entry.name);
        if (!relativePath) {
          showSnackbar("上传路径无效");
          return;
        }
        enqueueTasks([makeFileTask(file, relativePath)]);
        return;
      }

      showSnackbar("没有可上传的项目");
      return;
    }

    enqueueSingleFile(dataTransfer.files || []);
  };

  const checkConflict = async (task) => {
    const response = await abortableFetch(buildApiUrl("/api/share/upload-check", task), {
      method: "GET",
      headers: { "X-API-Request": "true" }
    });
    if (!response.ok) {
      const message = await response.text();
      throw new Error(message || "上传检查失败");
    }
    return response.json();
  };

  const uploadDirectory = async (task, overwrite) => {
    const response = await abortableFetch(buildApiUrl("/api/share/upload", task, overwrite), {
      method: "POST",
      headers: { "X-API-Request": "true" }
    });
    if (!response.ok) {
      const message = await response.text();
      throw new Error(message || "创建文件夹失败");
    }
  };

  const resetTaskTiming = (task) => {
    task.startedAt = nowMs();
    task.finishedAt = 0;
    task.uploadedBytes = 0;
    task.completedChunkBytes = 0;
    task.hadConflict = false;
    task.uploadStarted = false;
  };

  const cleanupCanceledUpload = async (task) => {
    if (task.type !== "file" || task.hadConflict || !task.uploadStarted) return;
    try {
      const response = await fetch(buildApiUrl("/api/share/upload-cancel", task, false), {
        method: "POST",
        headers: { "X-API-Request": "true" }
      });
      if (!response.ok) {
        showSnackbar("未完成文件清理失败");
      }
    } catch (_error) {
      showSnackbar("未完成文件清理失败");
    }
  };

  const uploadFileChunk = (task, overwrite, start, end, totalBytes, onProgress) => new Promise((resolve, reject) => {
    if (cancelRequested) {
      reject(createCanceledError());
      return;
    }
    const chunk = start < end ? task.file.slice(start, end) : new Blob([]);
    const xhr = new XMLHttpRequest();
    activeUploadXhr = xhr;
    const clearActiveXhr = () => {
      if (activeUploadXhr === xhr) {
        activeUploadXhr = null;
      }
    };
    xhr.open("POST", buildApiUrl("/api/share/upload", task, overwrite), true);
    xhr.setRequestHeader("X-API-Request", "true");
    if (totalBytes > 0) {
      xhr.setRequestHeader("Content-Range", "bytes " + start + "-" + (end - 1) + "/" + totalBytes);
    }
    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable || event.total <= 0) return;
      const chunkLoaded = start + event.loaded;
      const effectiveTotal = totalBytes || task.totalBytes || event.total;
      task.uploadedBytes = Math.min(effectiveTotal, chunkLoaded);
      task.progress = effectiveTotal > 0 ? Math.min(99, (task.uploadedBytes / effectiveTotal) * 100) : 99;
      if (onProgress) {
        onProgress(task.progress, task.uploadedBytes, effectiveTotal);
      } else {
        renderQueue();
      }
    };
    xhr.onload = () => {
      clearActiveXhr();
      if (cancelRequested) {
        reject(createCanceledError());
        return;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
      } else {
        reject(new Error(xhr.responseText || "上传失败"));
      }
    };
    xhr.onerror = () => {
      clearActiveXhr();
      reject(new Error("网络异常，上传失败"));
    };
    xhr.onabort = () => {
      clearActiveXhr();
      reject(createCanceledError());
    };
    task.uploadStarted = true;
    xhr.send(chunk);
  });

  const uploadFile = async (task, overwrite, onProgress) => {
    const totalBytes = getTaskTotalBytes(task);
    task.totalBytes = totalBytes;

    if (totalBytes === 0) {
      await uploadFileChunk(task, overwrite, 0, 0, 0, onProgress);
      task.progress = 100;
      task.uploadedBytes = 0;
      return;
    }

    let offset = 0;
    while (offset < totalBytes) {
      throwIfUploadCanceled();
      const end = Math.min(offset + UPLOAD_CHUNK_BYTES, totalBytes);
      await uploadFileChunk(task, overwrite && offset === 0, offset, end, totalBytes, onProgress);
      offset = end;
      task.completedChunkBytes = offset;
      task.uploadedBytes = offset;
      task.progress = Math.min(99, (offset / totalBytes) * 100);
      if (onProgress) {
        onProgress(task.progress, task.uploadedBytes, totalBytes);
      } else {
        renderQueue();
      }
    }
    task.progress = 100;
    task.uploadedBytes = totalBytes;
  };

  async function processSingleUploadTask(task, options) {
    const renderOwnProgress = !options || options.render !== false;
    const onProgress = options ? options.onProgress : null;
    resetTaskTiming(task);
    task.status = "running";
    task.progress = 0;
    task.message = "";
    if (renderOwnProgress) renderQueue();

    try {
      throwIfUploadCanceled();
      let overwrite = false;
      const conflict = await checkConflict(task);
      task.hadConflict = Boolean(conflict.exists);
      throwIfUploadCanceled();
      if (conflict.exists) {
        const shouldOverwrite = window.confirm(
          task.relativePath + " 已存在。\n\n确定覆盖，取消跳过。"
        );
        throwIfUploadCanceled();
        if (!shouldOverwrite) {
          task.status = "skipped";
          task.message = "用户选择跳过";
          task.progress = 100;
          task.finishedAt = nowMs();
          if (renderOwnProgress) renderQueue();
          return {
            status: "skipped",
            skipPrefix: task.type === "directory" ? task.relativePath + "/" : null
          };
        }
        overwrite = true;
      }

      if (task.type === "directory") {
        await uploadDirectory(task, overwrite);
      } else {
        await uploadFile(task, overwrite, onProgress);
      }

      task.status = "done";
      task.progress = 100;
      task.uploadedBytes = getTaskTotalBytes(task);
      task.finishedAt = nowMs();
      completedUploads += 1;
      if (renderOwnProgress) renderQueue();
      return { status: "done", skipPrefix: null };
    } catch (error) {
      if (isCanceledError(error)) {
        await cleanupCanceledUpload(task);
        task.status = "canceled";
        task.message = "已取消";
        task.finishedAt = nowMs();
        if (renderOwnProgress) renderQueue();
      }
      throw error;
    }
  }

  async function processFolderTask(folderTask) {
    const children = folderTask.children || [];
    resetTaskTiming(folderTask);
    folderTask.status = "running";
    folderTask.progress = 0;
    folderTask.message = "上传";
    renderQueue();

    if (children.length === 0) {
      folderTask.status = "skipped";
      folderTask.message = "没有可上传的项目";
      folderTask.progress = 100;
      folderTask.finishedAt = nowMs();
      renderQueue();
      return;
    }

    const skippedPrefixes = [];
    let completedBytes = 0;
    let finishedCount = 0;

    const updateFolderProgress = (child, childProgress, childUploadedBytes, childTotalBytes) => {
      const totalBytes = folderTask.totalBytes || 0;
      if (totalBytes > 0) {
        const currentBytes = child.type === "file"
          ? Math.max(0, Math.min(childUploadedBytes || 0, childTotalBytes || child.totalBytes || 0))
          : 0;
        folderTask.uploadedBytes = Math.min(totalBytes, completedBytes + currentBytes);
        folderTask.progress = Math.min(100, (folderTask.uploadedBytes / totalBytes) * 100);
      } else {
        const currentProgress = Math.max(0, Math.min(100, childProgress || 0)) / 100;
        folderTask.progress = ((finishedCount + currentProgress) / children.length) * 100;
      }
      renderQueue();
    };

    for (const child of children) {
      throwIfUploadCanceled();
      const skippedByParent = skippedPrefixes.some((prefix) => child.relativePath.startsWith(prefix));
      if (skippedByParent) {
        child.status = "skipped";
        child.message = "父文件夹已跳过";
        child.progress = 100;
        child.finishedAt = nowMs();
        finishedCount += 1;
        updateFolderProgress(child, 100, child.uploadedBytes || 0, child.totalBytes || 0);
        continue;
      }

      try {
        const result = await processSingleUploadTask(child, {
          render: false,
          onProgress: (childProgress, childUploadedBytes, childTotalBytes) => {
            updateFolderProgress(child, childProgress, childUploadedBytes, childTotalBytes);
          }
        });
        if (result && result.skipPrefix) {
          skippedPrefixes.push(result.skipPrefix);
        }
      } catch (error) {
        if (isCanceledError(error)) {
          markTaskCanceled(folderTask);
          renderQueue();
          throw error;
        }
        folderTask.status = "error";
        folderTask.message = error && error.message ? error.message : "上传失败";
        renderQueue();
        throw error;
      }

      if (child.type === "file") {
        completedBytes += child.totalBytes || 0;
      }
      finishedCount += 1;
      updateFolderProgress(child, 100, child.uploadedBytes || child.totalBytes || 0, child.totalBytes || 0);
    }

    const doneCount = children.filter((child) => child.status === "done").length;
    folderTask.status = doneCount > 0 ? "done" : "skipped";
    folderTask.message = doneCount > 0 ? "上传" : "用户选择跳过";
    folderTask.progress = 100;
    folderTask.uploadedBytes = folderTask.totalBytes;
    folderTask.finishedAt = nowMs();
    renderQueue();
  }

  async function processTask(task) {
    if (task.type === "folder") {
      await processFolderTask(task);
      return;
    }

    await processSingleUploadTask(task);
  }

  async function processQueue() {
    if (processing) return;
    processing = true;
    cancelRequested = false;
    activeUploadXhr = null;
    activeAbortController = null;
    setUploadDialogBusy(true);
    setUploadCanceling(false);
    wakeLockController.begin();
    let wasCanceled = false;

    try {
      while (true) {
        const task = queue.find((item) => item.status === "pending");
        if (!task) break;

        try {
          await processTask(task);
        } catch (error) {
          if (isCanceledError(error)) {
            wasCanceled = true;
            markTaskCanceled(task);
            queue.forEach(markTaskCanceled);
            renderQueue();
            break;
          }
          task.status = "error";
          task.message = error && error.message ? error.message : "上传失败";
          renderQueue();
        }
      }
    } finally {
      processing = false;
      activeUploadXhr = null;
      activeAbortController = null;
      stopStatsTimer();
      setUploadControlsDisabled(false);
      setUploadDialogBusy(false);
      renderQueue();
      wakeLockController.end();
    }

    if (wasCanceled || queue.some((item) => item.status === "canceled")) {
      showSnackbar("上传已取消");
      cancelRequested = false;
      return;
    }
    cancelRequested = false;

    if (completedUploads > 0 && queue.every((item) => item.status === "done" || item.status === "skipped")) {
      showSnackbar("上传完成");
      setUploadCompletionVisible(true);
      refreshAfterUploadBtn.focus();
    }
  }

  refreshAfterUploadBtn.addEventListener("click", () => {
    window.location.reload();
  });

  cancelUploadBtn.addEventListener("click", () => {
    if (!processing || cancelRequested) return;
    cancelRequested = true;
    setUploadCanceling(true);
    showSnackbar("正在取消上传");
    if (activeUploadXhr) {
      activeUploadXhr.abort();
    }
    if (activeAbortController) {
      activeAbortController.abort();
    }
  });

  closeUploadBtn.addEventListener("click", () => {
    closeUploadModal();
  });
  uploadOverlay.addEventListener("click", (event) => {
    if (event.target === uploadOverlay && !processing) {
      closeUploadModal();
    }
  });
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    if (!uploadOverlay.classList.contains("hidden") && !processing) {
      closeUploadModal();
    }
  });

  fileButton.addEventListener("click", () => fileInput.click());
  directoryButton.addEventListener("click", () => directoryInput.click());
  fileInput.addEventListener("change", () => {
    enqueueSingleFile(fileInput.files);
    fileInput.value = "";
  });
  directoryInput.addEventListener("change", () => {
    enqueueFolderFiles(directoryInput.files);
    directoryInput.value = "";
  });

  ["dragenter", "dragover"].forEach((eventName) => {
    document.addEventListener(eventName, (event) => {
      if (!hasDraggedFiles(event.dataTransfer)) return;
      event.preventDefault();
      if (isQueueBusy()) {
        event.dataTransfer.dropEffect = "none";
        return;
      }
      event.dataTransfer.dropEffect = "copy";
      if (eventName === "dragenter") {
        dragDepth += 1;
      }
      setPageDropActive(true);
    });
  });

  document.addEventListener("dragleave", (event) => {
    if (!hasDraggedFiles(event.dataTransfer)) return;
    event.preventDefault();
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0) {
      setPageDropActive(false);
    }
  });

  window.addEventListener("dragend", () => {
    dragDepth = 0;
    setPageDropActive(false);
  });

  window.addEventListener("blur", () => {
    dragDepth = 0;
    setPageDropActive(false);
  });

  document.addEventListener("drop", async (event) => {
    if (!hasDraggedFiles(event.dataTransfer) && !(event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files.length)) {
      return;
    }
    event.preventDefault();
    dragDepth = 0;
    setPageDropActive(false);
    if (isQueueBusy()) {
      showSnackbar("上传进行中，请等待完成");
      return;
    }
    try {
      await enqueueDroppedItems(event.dataTransfer);
    } catch (error) {
      showSnackbar(error && error.message ? error.message : "读取拖拽项目失败");
    }
  });
}
