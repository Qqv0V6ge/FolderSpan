(function(global) {
  const ZIP_WORKER_URL = "/static/workers/folder-zip-worker.js";

  async function startStreamedZipDownload(options) {
    const messages = options.messages;
    let worker = null;
    let writer = null;
    let writeChain = Promise.resolve();
    let outputStarted = false;
    let canceled = false;
    const key = taskKey();
    const abortController = new global.AbortController();
    const stats = {
      discovered: 0,
      files: 0,
      bytes: 0
    };

    const cancel = () => {
      canceled = true;
      abortController.abort();
      if (worker) {
        worker.postMessage({
          type: "ABORT_FOLDER_TASK",
          key,
          message: messages.streamZipCanceled || "已取消"
        });
        worker.terminate();
      }
      if (writer && typeof writer.abort === "function") {
        writer.abort(messages.streamZipCanceled || "已取消").catch(() => {});
      }
      options.setStatus(messages.streamZipCanceled || "已取消打包下载");
      options.setDownloading(false);
      options.setActiveDownload(null);
    };

    options.setActiveDownload({ cancel });
    options.setDownloading(true);
    options.setProgress(8);
    options.setStatus(messages.streamZipChooseSaveLocation || "请选择 ZIP 保存位置...");

    try {
      if (!global.ReadableStream || !global.Worker) {
        throw new Error(messages.streamZipNoReadableStream || "当前浏览器不支持流式读取文件");
      }

      const filename = buildArchiveFileName();
      writer = await createConfirmedZipWriter(filename, messages);
      if (canceled) return;
      if (typeof options.onDownloadStarted === "function") {
        options.onDownloadStarted();
      }
      worker = new global.Worker(ZIP_WORKER_URL);

      const donePromise = new Promise((resolve, reject) => {
        worker.onmessage = function(event) {
          const data = event.data || {};
          if (data.type === "FOLDER_ZIP_CHUNK") {
            outputStarted = true;
            const chunk = new Uint8Array(data.chunk);
            writeChain = writeChain
              .then(() => writer.write(chunk));
            if (data.isLast === true) {
              writeChain.then(resolve).catch(reject);
            }
            return;
          }
          if (data.type === "FOLDER_ZIP_DONE") {
            writeChain.then(resolve).catch(reject);
            return;
          }
          if (data.type === "FOLDER_ZIP_ERROR") {
            reject(new Error(data.message || "ZIP worker error"));
          }
        };
        worker.onerror = function(error) {
          reject(new Error(error.message || "ZIP worker error"));
        };
      });

      worker.postMessage({
        type: "INIT_FOLDER_TASK",
        key,
        rootPrefix: archiveRootName(),
        fileCount: 1
      });

      options.setStatus(messages.streamZipPreparing || "正在准备打包下载...");
      await walkAndZipCurrentPath({
        key,
        worker,
        abortSignal: abortController.signal,
        stats,
        messages,
        setStatus: options.setStatus,
        setProgress: options.setProgress
      });

      if (stats.discovered === 0) {
        options.setStatus(messages.streamZipEmpty || "当前目录没有可打包的内容");
      }

      worker.postMessage({ type: "FINALIZE_FOLDER_TASK", key });
      await donePromise;
      await writeChain;
      await writer.close();
      worker.terminate();
      options.setProgress(100);
      options.setStatus(
        messages.streamZipDone
          ? messages.streamZipDone(stats.files, stats.bytes)
          : "ZIP 打包完成"
      );
    } catch (error) {
      if (canceled) return;
      if (worker) worker.terminate();
      if (writer && typeof writer.abort === "function") {
        writer.abort(error).catch(() => {});
      }
      if (isSaveCanceledError(error)) {
        options.setProgress(0);
        options.setStatus(messages.streamZipSaveCanceled || "已取消保存");
        return;
      }
      const message = error && error.message ? error.message : "";
      const text = messages.streamZipFailed ? messages.streamZipFailed(message) : "打包下载失败";
      options.setStatus(outputStarted ? text : text + "，可使用脚本下载。");
    } finally {
      if (!canceled) {
        options.setDownloading(false);
        options.setActiveDownload(null);
      }
    }
  }

  async function createConfirmedZipWriter(filename, messages) {
    if (typeof global.showSaveFilePicker !== "function") {
      throw new Error(
        messages.streamZipSavePickerUnavailable ||
          "当前浏览器不支持确认保存后打包 ZIP，可使用脚本下载。"
      );
    }
    const handle = await global.showSaveFilePicker({
      suggestedName: filename,
      types: [
        {
          description: "ZIP",
          accept: {
            "application/zip": [".zip"]
          }
        }
      ],
      excludeAcceptAllOption: false
    });
    if (!handle || typeof handle.createWritable !== "function") {
      throw new Error(messages.streamZipSavePickerUnavailable || "无法创建保存文件");
    }
    return handle.createWritable();
  }

  function isSaveCanceledError(error) {
    return !!error && (error.name === "AbortError" || error.name === "NotAllowedError");
  }

  async function walkAndZipCurrentPath(options) {
    const rootPath = normalizeRequestPath(global.location.pathname);
    await walkDirectory(rootPath, "", options);
  }

  async function walkDirectory(path, relativeRoot, options) {
    throwIfAborted(options.abortSignal);
    const currentDirectory = currentDirectoryLabel(relativeRoot);
    const entries = await fetchDirectory(path, options.abortSignal);
    options.stats.discovered += entries.length;
    options.setStatus(
      options.messages.streamZipWalking
        ? options.messages.streamZipWalking(currentDirectory)
        : currentDirectory
    );

    for (let i = 0; i < entries.length; i += 1) {
      throwIfAborted(options.abortSignal);
      const item = entries[i];
      const name = safeEntryName(item.name);
      if (!name) continue;
      const relativePath = relativeRoot ? relativeRoot + "/" + name : name;

      if (item.isDirectory === true) {
        options.worker.postMessage({
          type: "REGISTER_DIRECTORY",
          key: options.key,
          relativePath
        });
        await walkDirectory(item.path, relativePath, options);
      } else {
        await streamFileIntoZip(item, relativePath, currentDirectory, options);
      }
    }
  }

  async function fetchDirectory(path, abortSignal) {
    const response = await global.fetch(path, {
      method: "GET",
      credentials: "same-origin",
      headers: {
        "X-API-Request": "true",
        "Accept": "application/json"
      },
      signal: abortSignal
    });
    if (!response.ok) {
      throw new Error("HTTP " + response.status);
    }
    const data = await response.json();
    return Array.isArray(data) ? data : [];
  }

  async function streamFileIntoZip(item, relativePath, currentDirectory, options) {
    const filePath = item.path || "";
    if (!filePath) return;
    options.stats.files += 1;
    options.setProgress(Math.min(88, 12 + options.stats.files * 4));
    options.setStatus(
      options.messages.streamZipReading
        ? options.messages.streamZipReading(currentDirectory)
        : currentDirectory
    );
    options.worker.postMessage({
      type: "BEGIN_FILE_ENTRY",
      key: options.key,
      filePath,
      relativePath
    });

    const response = await global.fetch(filePath, {
      method: "GET",
      credentials: "same-origin",
      headers: {
        "X-API-Request": "true"
      },
      signal: options.abortSignal
    });
    if (!response.ok) {
      throw new Error("HTTP " + response.status);
    }
    if (!response.body || typeof response.body.getReader !== "function") {
      throw new Error(options.messages.streamZipNoReadableStream || "当前浏览器不支持流式读取文件");
    }

    const reader = response.body.getReader();
    while (true) {
      throwIfAborted(options.abortSignal);
      const result = await reader.read();
      if (result.done) break;
      const chunk = result.value;
      const buffer = chunk.buffer.slice(chunk.byteOffset, chunk.byteOffset + chunk.byteLength);
      options.stats.bytes += chunk.byteLength;
      options.worker.postMessage({
        type: "FOLDER_FILE_CHUNK",
        key: options.key,
        filePath,
        chunk: buffer,
        isLast: false
      }, [buffer]);
    }
    options.worker.postMessage({
      type: "FOLDER_FILE_CHUNK",
      key: options.key,
      filePath,
      chunk: new ArrayBuffer(0),
      isLast: true
    });
  }

  function throwIfAborted(signal) {
    if (signal && signal.aborted) {
      if (typeof global.DOMException === "function") {
        throw new global.DOMException("Aborted", "AbortError");
      }
      throw new Error("Aborted");
    }
  }

  function normalizeRequestPath(path) {
    return path && path.startsWith("/") ? path : "/";
  }

  function safeEntryName(name) {
    return String(name || "")
      .replace(/[\\/:*?"<>|]/g, "_")
      .replace(/\u0000/g, "")
      .trim();
  }

  function archiveRootName() {
    const parts = global.location.pathname.split("/").filter(Boolean);
    const last = parts.length ? parts[parts.length - 1] : "share";
    try {
      return safeEntryName(decodeURIComponent(last)) || "share";
    } catch (_) {
      return safeEntryName(last) || "share";
    }
  }

  function buildArchiveFileName() {
    return archiveRootName() + ".zip";
  }

  function currentDirectoryLabel(relativeRoot) {
    return relativeRoot ? archiveRootName() + "/" + relativeRoot : archiveRootName();
  }

  function taskKey() {
    return "batch-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);
  }

  global.FolderSpanZipDownload = {
    startStreamedZipDownload
  };
})(typeof window !== "undefined" ? window : globalThis);
