@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.service.webrtc.download

import strings.AppStrings

import com.folderspan.service.webrtc.controller.core.IncomingFileChunkWriter
import kotlinx.coroutines.await
import kotlin.js.Promise

object WebRtcBrowserDownloadSupport : WebRtcBrowserDownloadProvider {
    init {
        ensureWebRtcBrowserDownloadApi()
    }

    override fun isSecureContext(): Boolean =
        isWebRtcBrowserSecureContext()

    override fun createFileWriter(fileName: String, mimeType: String?): IncomingFileChunkWriter =
        BrowserFileWriter(fileName, mimeType)

    override suspend fun createZipSession(fileName: String, rootName: String, fileCount: Int): WebRtcBrowserZipSession? {
        ensureWebRtcBrowserDownloadApi()
        val key = webRtcBrowserCreateZipSession(fileName, rootName, fileCount).await()?.toString()
            ?: return null
        return BrowserZipSession(key)
    }

    private class BrowserFileWriter(
        private val fileName: String,
        private val mimeType: String?,
    ) : IncomingFileChunkWriter {
        private val key = "file-$fileName-${kotlin.random.Random.nextLong()}"

        override suspend fun writeChunk(path: String, fileSize: Long, data: ByteArray, offset: Long): Result<Boolean> =
            runCatching {
                ensureWebRtcBrowserDownloadApi()
                webRtcBrowserWriteFileChunk(
                    key = key,
                    fileName = fileName,
                    mimeType = mimeType ?: "",
                    fileSize = fileSize,
                    offset = offset,
                    bytes = data.toJsByteArray(),
                ).await()
                true
            }

        override suspend fun prepare(path: String, fileSize: Long): Result<Boolean> =
            runCatching {
                ensureWebRtcBrowserDownloadApi()
                webRtcBrowserPrepareFile(key, fileName, mimeType ?: "").await()
                true
            }

        override suspend fun commit(path: String, fileSize: Long): Result<Boolean> =
            runCatching {
                ensureWebRtcBrowserDownloadApi()
                webRtcBrowserCloseFile(key).await()
                true
            }

        override fun cleanup(path: String) {
            ensureWebRtcBrowserDownloadApi()
            webRtcBrowserAbortFile(key)
        }
    }

    private class BrowserZipSession(private val key: String) : WebRtcBrowserZipSession {
        override suspend fun registerDirectory(relativePath: String): Result<Boolean> =
            runCatching {
                ensureWebRtcBrowserDownloadApi()
                webRtcBrowserRegisterZipDirectory(key, relativePath).await()
                true
            }

        override suspend fun beginFile(relativePath: String): Result<Boolean> =
            runCatching {
                ensureWebRtcBrowserDownloadApi()
                webRtcBrowserBeginZipFile(key, relativePath).await()
                true
            }

        override suspend fun writeFileChunk(fileKey: String, bytes: ByteArray, isLast: Boolean): Result<Boolean> =
            runCatching {
                ensureWebRtcBrowserDownloadApi()
                webRtcBrowserWriteZipFileChunk(key, fileKey, bytes.toJsByteArray(), isLast).await()
                true
            }

        override suspend fun finalize(): Result<Boolean> =
            runCatching {
                ensureWebRtcBrowserDownloadApi()
                webRtcBrowserFinalizeZip(key).await()
                true
            }

        override fun abort(reason: String) {
            ensureWebRtcBrowserDownloadApi()
            webRtcBrowserAbortZip(key, reason)
        }
    }
}

@JsFun(
    """
    () => {
      if (globalThis.__folderSpanWebRtcBrowserDownloadApi) {
        return true;
      }

      const fileHandles = {};
      const fileWriters = {};
      const fileQueues = {};
      let zipSeq = 1;
      const zipSessions = {};

      const normalizeIncomingBytes = (raw) => {
        if (!raw) return new Uint8Array(0);
        if (raw instanceof Uint8Array) return raw;
        if (raw instanceof ArrayBuffer) return new Uint8Array(raw);
        if (raw.buffer instanceof ArrayBuffer) {
          try {
            return new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength);
          } catch (_) {}
        }
        if (typeof raw === "string") return base64ToBytes(raw);
        try {
          return new Uint8Array(raw);
        } catch (_) {
          return new Uint8Array(0);
        }
      };

      const base64ToBytes = (base64) => {
        if (!base64) return new Uint8Array(0);
        const binary = atob(base64);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i += 1) {
          bytes[i] = binary.charCodeAt(i);
        }
        return bytes;
      };

      const enqueue = (queueMap, key, task) => {
        const tail = queueMap[key] || Promise.resolve();
        const next = tail.then(task, task);
        queueMap[key] = next.catch(() => {});
        return next;
      };

      const ensurePicker = () => {
        const locationValue = globalThis.location || {};
        const protocol = String(locationValue.protocol || "").toLowerCase();
        const hostname = String(locationValue.hostname || "").toLowerCase();
        const local = hostname === "localhost" ||
          hostname === "127.0.0.1" ||
          hostname === "::1" ||
          hostname.endsWith(".localhost");
        if (globalThis.isSecureContext !== true || (protocol !== "https:" && !(protocol === "http:" && local))) {
          throw new Error(AppStrings.ui_web_version_must_opened_over_https_receive_files);
        }
        if (typeof globalThis.showSaveFilePicker !== "function") {
          throw new Error(AppStrings.ui_current_browser_does_not_support_saving_files);
        }
      };

      const normalizeFileName = (name, fallback) => {
        const value = String(name || "").split("\\\\").join("/").split("/").pop() || fallback;
        return value || fallback;
      };

      const pickFile = async (key, fileName, mimeType) => {
        if (fileHandles[key]) return fileHandles[key];
        ensurePicker();
        const mime = mimeType || "application/octet-stream";
        const accept = {};
        accept[mime] = [];
        const handle = await globalThis.showSaveFilePicker({
          suggestedName: normalizeFileName(fileName, "download.bin"),
          types: [{ description: "File", accept }],
          excludeAcceptAllOption: false
        });
        fileHandles[key] = handle;
        return handle;
      };

      const writeFileChunk = async (key, fileName, mimeType, fileSize, offset, rawBytes) => {
        const bytes = normalizeIncomingBytes(rawBytes);
        const handle = await pickFile(key, fileName, mimeType);
        return enqueue(fileQueues, key, async () => {
          let writer = fileWriters[key];
          if (!writer) {
            writer = await handle.createWritable({ keepExistingData: true });
            fileWriters[key] = writer;
            const targetSize = Math.max(Number(fileSize) || 0, (Number(offset) || 0) + bytes.length);
            await writer.truncate(targetSize);
          }
          await writer.write({
            type: "write",
            position: Number(offset) || 0,
            data: bytes
          });
          return true;
        });
      };

      const prepareFile = async (key, fileName, mimeType) => {
        await pickFile(key, fileName, mimeType);
        return true;
      };

      const closeFile = (key) => enqueue(fileQueues, key, async () => {
        const writer = fileWriters[key];
        delete fileWriters[key];
        delete fileHandles[key];
        if (!writer) return true;
        await writer.close();
        return true;
      });

      const abortFile = (key) => {
        const writer = fileWriters[key];
        delete fileWriters[key];
        delete fileHandles[key];
        delete fileQueues[key];
        if (writer && typeof writer.abort === "function") {
          try { writer.abort(); } catch (_) {}
        }
        return true;
      };

      const cleanupZip = (key) => {
        const session = zipSessions[key];
        if (!session) return;
        try { session.worker && session.worker.terminate(); } catch (_) {}
        delete zipSessions[key];
      };

      const createZipSession = async (fileName, rootName, fileCount) => {
        ensurePicker();
        const key = "zip-" + Date.now() + "-" + zipSeq++;
        const handle = await globalThis.showSaveFilePicker({
          suggestedName: normalizeFileName(fileName, "download.zip"),
          types: [{ description: "ZIP", accept: { "application/zip": [".zip"] } }],
          excludeAcceptAllOption: false
        });
        const writable = await handle.createWritable();
        const worker = new Worker("/static/workers/folder-zip-worker.js");
        let doneResolve;
        let doneReject;
        const done = new Promise((resolve, reject) => {
          doneResolve = resolve;
          doneReject = reject;
        });
        zipSessions[key] = {
          worker,
          writable,
          queue: Promise.resolve(),
          done,
          doneResolve,
          doneReject
        };
        worker.onmessage = (event) => {
          const data = event && event.data ? event.data : {};
          if (data.key !== key) return;
          if (data.type === "FOLDER_ZIP_CHUNK") {
            const session = zipSessions[key];
            if (!session) return;
            const bytes = data.chunk ? new Uint8Array(data.chunk) : new Uint8Array(0);
            session.queue = session.queue
              .then(() => session.writable.write(bytes))
              .then(() => {
                if (data.isLast === true) {
                  return session.writable.close().then(() => {
                    cleanupZip(key);
                    doneResolve(true);
                  });
                }
                return true;
              });
            session.queue.catch((error) => {
              cleanupZip(key);
              doneReject(error || new Error(AppStrings.ui_zip_write_failed));
            });
          } else if (data.type === "FOLDER_ZIP_DONE") {
            const sessionDone = zipSessions[key];
            if (!sessionDone) return;
            sessionDone.queue
              .then(() => sessionDone.writable.close())
              .then(() => {
                cleanupZip(key);
                doneResolve(true);
              }, (error) => {
                cleanupZip(key);
                doneReject(error || new Error(AppStrings.ui_zip_write_failed));
              });
          } else if (data.type === "FOLDER_ZIP_ERROR") {
            cleanupZip(key);
            doneReject(new Error(data.message || AppStrings.ui_zip_packaging_failed));
          }
        };
        worker.onerror = (error) => {
          cleanupZip(key);
          doneReject(new Error(error && error.message ? error.message : AppStrings.ui_zip_worker_failed));
        };
        worker.postMessage({
          type: "INIT_FOLDER_TASK",
          key,
          rootPrefix: rootName == null ? "" : String(rootName),
          fileCount: fileCount || 0
        });
        return key;
      };

      const postZip = (key, message) => {
        const session = zipSessions[key];
        if (!session) return Promise.resolve(false);
        message.key = key;
        session.worker.postMessage(message);
        return Promise.resolve(true);
      };

      const postZipChunk = (key, fileKey, rawBytes, isLast) => {
        const session = zipSessions[key];
        if (!session) return Promise.resolve(false);
        const bytes = normalizeIncomingBytes(rawBytes);
        const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
        session.worker.postMessage({
          type: "FOLDER_FILE_CHUNK",
          key,
          filePath: fileKey,
          chunk: buffer,
          isLast: isLast === true
        }, [buffer]);
        return Promise.resolve(true);
      };

      const finalizeZip = (key) => {
        const session = zipSessions[key];
        if (!session) return Promise.resolve(false);
        session.worker.postMessage({ type: "FINALIZE_FOLDER_TASK", key });
        return session.done;
      };

      const abortZip = (key, reason) => {
        const session = zipSessions[key];
        if (!session) return true;
        try {
          session.worker.postMessage({ type: "ABORT_FOLDER_TASK", key, message: reason || AppStrings.ui_canceled });
        } catch (_) {}
        try {
          if (session.writable && typeof session.writable.abort === "function") {
            session.writable.abort();
          }
        } catch (_) {}
        cleanupZip(key);
        return true;
      };

      globalThis.__folderSpanWebRtcBrowserDownloadApi = {
        prepareFile,
        writeFileChunk,
        closeFile,
        abortFile,
        createZipSession,
        registerZipDirectory: (key, relativePath) => postZip(key, { type: "REGISTER_DIRECTORY", relativePath: relativePath || "" }),
        beginZipFile: (key, relativePath) => postZip(key, { type: "BEGIN_FILE_ENTRY", relativePath: relativePath || "", filePath: relativePath || "" }),
        writeZipFileChunk: postZipChunk,
        finalizeZip,
        abortZip
      };
      return true;
    }
    """
)
private external fun ensureWebRtcBrowserDownloadApi(): Boolean

@JsFun(
    """
    () => {
      const locationValue = globalThis.location || {};
      const protocol = String(locationValue.protocol || "").toLowerCase();
      const hostname = String(locationValue.hostname || "").toLowerCase();
      const local = hostname === "localhost" ||
        hostname === "127.0.0.1" ||
        hostname === "::1" ||
        hostname.endsWith(".localhost");
      return globalThis.isSecureContext === true &&
        (protocol === "https:" || (protocol === "http:" && local));
    }
    """
)
private external fun isWebRtcBrowserSecureContext(): Boolean

@JsFun("(key, fileName, mimeType, fileSize, offset, bytes) => globalThis.__folderSpanWebRtcBrowserDownloadApi.writeFileChunk(key, fileName, mimeType, fileSize, offset, bytes)")
private external fun webRtcBrowserWriteFileChunk(
    key: String,
    fileName: String,
    mimeType: String,
    fileSize: Long,
    offset: Long,
    bytes: JsAny,
): Promise<JsAny?>

@JsFun("(key, fileName, mimeType) => globalThis.__folderSpanWebRtcBrowserDownloadApi.prepareFile(key, fileName, mimeType)")
private external fun webRtcBrowserPrepareFile(
    key: String,
    fileName: String,
    mimeType: String,
): Promise<JsAny?>

@JsFun("(key) => globalThis.__folderSpanWebRtcBrowserDownloadApi.closeFile(key)")
private external fun webRtcBrowserCloseFile(key: String): Promise<JsAny?>

@JsFun("(key) => globalThis.__folderSpanWebRtcBrowserDownloadApi.abortFile(key)")
private external fun webRtcBrowserAbortFile(key: String): Boolean

@JsFun("(fileName, rootName, fileCount) => globalThis.__folderSpanWebRtcBrowserDownloadApi.createZipSession(fileName, rootName, fileCount)")
private external fun webRtcBrowserCreateZipSession(fileName: String, rootName: String, fileCount: Int): Promise<JsAny?>

@JsFun("(key, relativePath) => globalThis.__folderSpanWebRtcBrowserDownloadApi.registerZipDirectory(key, relativePath)")
private external fun webRtcBrowserRegisterZipDirectory(key: String, relativePath: String): Promise<JsAny?>

@JsFun("(key, relativePath) => globalThis.__folderSpanWebRtcBrowserDownloadApi.beginZipFile(key, relativePath)")
private external fun webRtcBrowserBeginZipFile(key: String, relativePath: String): Promise<JsAny?>

@JsFun("(key, fileKey, bytes, isLast) => globalThis.__folderSpanWebRtcBrowserDownloadApi.writeZipFileChunk(key, fileKey, bytes, isLast)")
private external fun webRtcBrowserWriteZipFileChunk(
    key: String,
    fileKey: String,
    bytes: JsAny,
    isLast: Boolean,
): Promise<JsAny?>

@JsFun("(key) => globalThis.__folderSpanWebRtcBrowserDownloadApi.finalizeZip(key)")
private external fun webRtcBrowserFinalizeZip(key: String): Promise<JsAny?>

@JsFun("(key, reason) => globalThis.__folderSpanWebRtcBrowserDownloadApi.abortZip(key, reason)")
private external fun webRtcBrowserAbortZip(key: String, reason: String): Boolean

@JsFun(
    """
    (size, byteAt) => {
      size = Math.max(0, Number(size) || 0);
      const out = new Uint8Array(size);
      for (let index = 0; index < size; index += 1) {
        out[index] = Number(byteAt(index) || 0) & 255;
      }
      return out;
    }
    """
)
private external fun byteArrayToJsUint8Array(size: Int, byteAt: (Int) -> Int): JsAny

private fun ByteArray.toJsByteArray(): JsAny =
    byteArrayToJsUint8Array(size) { index -> this[index].toInt() and 0xFF }
