importScripts("/static/i18n.js");

const i18n = self.I18N;
const folderTasks = new Map();


/**
 * 准备 CommonJS 导出兼容层，方便 fflate 在 Worker 内加载。
 * @returns {{moduleRef: Object, exportsRef: Object}} 导出引用
 */
function prepareExportsShim() {
  let moduleRef = self.module;
  let exportsRef = self.exports;
  if (!moduleRef || typeof moduleRef !== "object") {
    moduleRef = { exports: {} };
  }
  if (!exportsRef || typeof exportsRef !== "object") {
    exportsRef = moduleRef.exports || {};
    moduleRef.exports = exportsRef;
  }
  self.module = moduleRef;
  self.exports = exportsRef;
  return { moduleRef: moduleRef, exportsRef: exportsRef };
}


/**
 * 尝试加载 fflate，并返回加载结果。
 * @returns {{ok: boolean, error: Error|null}} 加载结果
 */
function loadFflate() {
  const sources = ["fflate.min.js", "/static/workers/fflate.min.js"];
  let lastError = null;
  const shim = prepareExportsShim();
  for (let i = 0; i < sources.length; i += 1) {
    try {
      importScripts(sources[i]);
    } catch (error) {
      lastError = error;
    }
    if (!self.fflate && shim.moduleRef && shim.moduleRef.exports) {
      self.fflate = shim.moduleRef.exports;
    }
    if (self.fflate && self.fflate.Zip && self.fflate.ZipPassThrough) {
      return { ok: true, error: null };
    }
  }
  return { ok: false, error: lastError };
}

const loadResult = loadFflate();
const ZipClass = loadResult.ok ? self.fflate.Zip : null;
const ZipPassThroughClass = loadResult.ok ? self.fflate.ZipPassThrough : null;
const loadErrorMessage = loadResult.error && loadResult.error.message
  ? loadResult.error.message
  : null;


/**
 * 规范化相对路径，统一分隔符并去除前导斜杠。
 * @param {string} path 原始路径
 * @returns {string} 规范化路径
 */
function normalizeRelative(path) {
  if (!path) return "";
  let value = String(path);
  value = value.split("\\").join("/");
  while (value.startsWith("/")) {
    value = value.slice(1);
  }
  while (value.indexOf("//") !== -1) {
    value = value.replace("//", "/");
  }
  return value;
}


/**
 * 构建 zip 条目的完整名称。
 * @param {Object} task 打包任务
 * @param {string} relativePath 相对路径
 * @param {boolean} isDirectory 是否为目录
 * @returns {string} 条目名称
 */
function buildEntryName(task, relativePath, isDirectory) {
  const rel = normalizeRelative(relativePath || "");
  let prefix = task.rootPrefix ? normalizeRelative(task.rootPrefix) : "";
  if (prefix && !prefix.endsWith("/")) {
    prefix += "/";
  }
  if (isDirectory) {
    if (!rel) return prefix ? prefix : "";
    return prefix + (rel.endsWith("/") ? rel : rel + "/");
  }
  return prefix ? (rel ? prefix + rel : prefix.slice(0, -1)) : rel;
}


/**
 * Worker 消息入口，根据类型路由任务。
 * @param {MessageEvent} event 消息事件
 */
self.onmessage = function(event) {
  const data = event.data || {};
  if (!ZipClass || !ZipPassThroughClass) {
    if (data.key) {
      const message = i18n.worker.zipModuleUnavailable(loadErrorMessage);
      self.postMessage({ type: "FOLDER_ZIP_ERROR", key: data.key, message: message });
    }
    return;
  }
  switch (data.type) {
    case "INIT_FOLDER_TASK":
      initFolderTask(data);
      return;
    case "REGISTER_DIRECTORY":
      registerDirectoryEntry(data);
      return;
    case "BEGIN_FILE_ENTRY":
      beginFileEntry(data);
      return;
    case "FOLDER_FILE_CHUNK":
      handleFolderFileChunk(data);
      return;
    case "FINALIZE_FOLDER_TASK":
      finalizeFolderTask(data.key);
      return;
    case "ABORT_FOLDER_TASK":
      abortFolderTask(data.key, data.message);
      return;
    default:
      return;
  }
};


/**
 * 初始化目录打包任务。
 * @param {Object} data 初始化参数
 */
function initFolderTask(data) {
  const key = data.key;
  if (!key) return;
  const zip = new ZipClass(function(err, chunk, final) {
    if (err) {
      folderTasks.delete(key);
      self.postMessage({
        type: "FOLDER_ZIP_ERROR",
        key: key,
        message: err && err.message ? err.message : i18n.worker.zipFailed
      });
      return;
    }
    const output = chunk ? (chunk instanceof Uint8Array ? chunk : new Uint8Array(chunk)) : null;
    if (output && output.byteLength) {
      const buffer = output.buffer && typeof output.buffer.slice === "function"
        ? output.buffer.slice(output.byteOffset, output.byteOffset + output.byteLength)
        : new Uint8Array(output).buffer;
      self.postMessage({
        type: "FOLDER_ZIP_CHUNK",
        key: key,
        chunk: buffer,
        isLast: final === true
      }, [buffer]);
    } else if (final) {
      self.postMessage({ type: "FOLDER_ZIP_DONE", key: key });
    }
    if (final) {
      folderTasks.delete(key);
    }
  });
  folderTasks.set(key, {
    zip: zip,
    entries: new Map(),
    rootPrefix: data.rootPrefix || "",
    pendingFinalize: false
  });
  if (!data.fileCount) {
    zip.end();
  }
}


/**
 * 注册目录条目到 zip 中。
 * @param {Object} data 目录信息
 */
function registerDirectoryEntry(data) {
  const task = folderTasks.get(data.key);
  if (!task) return;
  const entryName = buildEntryName(task, data.relativePath, true);
  if (!entryName) return;
  const entry = new ZipPassThroughClass(entryName.endsWith("/") ? entryName : entryName + "/");
  task.zip.add(entry);
  entry.push(new Uint8Array(), true);
}


/**
 * 开始写入文件条目。
 * @param {Object} data 文件信息
 */
function beginFileEntry(data) {
  const task = folderTasks.get(data.key);
  if (!task) return;
  const entryName = buildEntryName(task, data.relativePath, false) || (task.rootPrefix || "file");
  const entry = new ZipPassThroughClass(entryName);
  task.zip.add(entry);
  task.entries.set(data.filePath, entry);
}


/**
 * 写入文件分片数据。
 * @param {Object} data 分片信息
 */
function handleFolderFileChunk(data) {
  const task = folderTasks.get(data.key);
  if (!task) return;
  const entry = task.entries.get(data.filePath);
  if (!entry) return;
  const chunk = data.chunk ? new Uint8Array(data.chunk) : new Uint8Array();
  entry.push(chunk, data.isLast === true);
  if (data.isLast) {
    task.entries.delete(data.filePath);
    if (task.pendingFinalize && task.entries.size === 0) {
      task.zip.end();
    }
  }
}


/**
 * 结束目录打包任务。
 * @param {string} key 任务标识
 */
function finalizeFolderTask(key) {
  const task = folderTasks.get(key);
  if (!task) return;
  task.pendingFinalize = true;
  if (task.entries.size === 0) {
    task.zip.end();
  }
}


/**
 * 中止目录打包任务。
 * @param {string} key 任务标识
 * @param {string} message 取消原因
 */
function abortFolderTask(key, message) {
  if (!folderTasks.has(key)) return;
  folderTasks.delete(key);
  self.postMessage({ type: "FOLDER_ZIP_ERROR", key: key, message: message || i18n.common.canceled });
}
