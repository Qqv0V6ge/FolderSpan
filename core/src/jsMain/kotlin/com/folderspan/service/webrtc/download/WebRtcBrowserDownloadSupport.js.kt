package com.folderspan.service.webrtc.download

import strings.AppStrings

import com.folderspan.service.webrtc.controller.core.IncomingFileChunkWriter
import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.random.Random

object WebRtcBrowserDownloadSupport : WebRtcBrowserDownloadProvider {
    private val api: dynamic = js(
        """
        (function () {
          if (globalThis.__folderSpanWebRtcBrowserDownloadApi) {
            return globalThis.__folderSpanWebRtcBrowserDownloadApi;
          }

          var fileHandles = {};
          var fileWriters = {};
          var fileQueues = {};
          var zipSeq = 1;
          var zipSessions = {};

          function normalizeIncomingBytes(raw) {
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
          }

          function base64ToBytes(base64) {
            if (!base64) return new Uint8Array(0);
            var binary = atob(base64);
            var bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i += 1) {
              bytes[i] = binary.charCodeAt(i);
            }
            return bytes;
          }

          function enqueue(queueMap, key, task) {
            var tail = queueMap[key] || Promise.resolve();
            var next = tail.then(task, task);
            queueMap[key] = next.then(function () {}, function () {});
            return next;
          }

          function ensurePicker() {
            var locationValue = globalThis.location || {};
            var protocol = String(locationValue.protocol || "").toLowerCase();
            var hostname = String(locationValue.hostname || "").toLowerCase();
            var local = hostname === "localhost" ||
              hostname === "127.0.0.1" ||
              hostname === "::1" ||
              hostname.endsWith(".localhost");
            if (globalThis.isSecureContext !== true || (protocol !== "https:" && !(protocol === "http:" && local))) {
              throw new Error(AppStrings.ui_web_version_must_opened_over_https_receive_files);
            }
            if (typeof globalThis.showSaveFilePicker !== "function") {
              throw new Error(AppStrings.ui_current_browser_does_not_support_saving_files);
            }
          }

          function normalizeFileName(name, fallback) {
            var value = String(name || "").split("\\\\").join("/").split("/").pop() || fallback;
            return value || fallback;
          }

          function pickFile(key, fileName, mimeType) {
            if (fileHandles[key]) return Promise.resolve(fileHandles[key]);
            ensurePicker();
            var mime = mimeType || "application/octet-stream";
            return Promise.resolve(globalThis.showSaveFilePicker({
              suggestedName: normalizeFileName(fileName, "download.bin"),
              types: [
                {
                  description: "File",
                  accept: (function () {
                    var accept = {};
                    accept[mime] = [];
                    return accept;
                  })()
                }
              ],
              excludeAcceptAllOption: false
            })).then(function (handle) {
              fileHandles[key] = handle;
              return handle;
            });
          }

          function writeFileChunk(key, fileName, mimeType, fileSize, offset, rawBytes) {
            var bytes = normalizeIncomingBytes(rawBytes);
            return pickFile(key, fileName, mimeType).then(function (handle) {
              return enqueue(fileQueues, key, function () {
                var writer = fileWriters[key];
                var prepare = writer
                  ? Promise.resolve()
                  : Promise.resolve(handle.createWritable({ keepExistingData: true })).then(function (created) {
                      writer = created;
                      fileWriters[key] = writer;
                    }).then(function () {
                      var targetSize = Math.max(Number(fileSize) || 0, (Number(offset) || 0) + bytes.length);
                      return Promise.resolve(writer.truncate(targetSize));
                    });
                return prepare
                  .then(function () {
                    return Promise.resolve(writer.write({
                      type: "write",
                      position: Number(offset) || 0,
                      data: bytes
                    }));
                  })
                  .then(function () { return true; });
              });
            });
          }

          function prepareFile(key, fileName, mimeType) {
            return pickFile(key, fileName, mimeType).then(function () { return true; });
          }

          function closeFile(key) {
            return enqueue(fileQueues, key, function () {
              var writer = fileWriters[key];
              delete fileWriters[key];
              delete fileHandles[key];
              if (!writer) return true;
              return Promise.resolve(writer.close()).then(function () { return true; });
            });
          }

          function abortFile(key) {
            var writer = fileWriters[key];
            delete fileWriters[key];
            delete fileHandles[key];
            delete fileQueues[key];
            if (writer && typeof writer.abort === "function") {
              try { writer.abort(); } catch (_) {}
            }
            return true;
          }

          function createZipSession(fileName, rootName, fileCount) {
            ensurePicker();
            var key = "zip-" + Date.now() + "-" + zipSeq++;
            var worker = null;
            var writable = null;
            var queue = Promise.resolve();
            var doneResolve = null;
            var doneReject = null;
            var done = new Promise(function (resolve, reject) {
              doneResolve = resolve;
              doneReject = reject;
            });
            return Promise.resolve(globalThis.showSaveFilePicker({
              suggestedName: normalizeFileName(fileName, "download.zip"),
              types: [
                {
                  description: "ZIP",
                  accept: { "application/zip": [".zip"] }
                }
              ],
              excludeAcceptAllOption: false
            })).then(function (handle) {
              return Promise.resolve(handle.createWritable());
            }).then(function (createdWritable) {
              writable = createdWritable;
              worker = new Worker("/static/workers/folder-zip-worker.js");
              zipSessions[key] = {
                worker: worker,
                writable: writable,
                queue: queue,
                done: done,
                doneResolve: doneResolve,
                doneReject: doneReject
              };
              worker.onmessage = function (event) {
                var data = event && event.data ? event.data : {};
                if (data.key !== key) return;
                if (data.type === "FOLDER_ZIP_CHUNK") {
                  var bytes = data.chunk ? new Uint8Array(data.chunk) : new Uint8Array(0);
                  var session = zipSessions[key];
                  if (!session) return;
                  session.queue = session.queue
                    .then(function () { return session.writable.write(bytes); })
                    .then(function () {
                      if (data.isLast === true) {
                        return session.writable.close().then(function () {
                          cleanupZip(key);
                          doneResolve(true);
                        });
                      }
                      return true;
                    });
                  session.queue.catch(function (error) {
                    cleanupZip(key);
                    doneReject(error || new Error(AppStrings.ui_zip_write_failed));
                  });
                } else if (data.type === "FOLDER_ZIP_DONE") {
                  var sessionDone = zipSessions[key];
                  if (!sessionDone) return;
                  sessionDone.queue
                    .then(function () { return sessionDone.writable.close(); })
                    .then(function () {
                      cleanupZip(key);
                      doneResolve(true);
                    }, function (error) {
                      cleanupZip(key);
                      doneReject(error || new Error(AppStrings.ui_zip_write_failed));
                    });
                } else if (data.type === "FOLDER_ZIP_ERROR") {
                  cleanupZip(key);
                  doneReject(new Error(data.message || AppStrings.ui_zip_packaging_failed));
                }
              };
              worker.onerror = function (error) {
                cleanupZip(key);
                doneReject(new Error(error && error.message ? error.message : AppStrings.ui_zip_worker_failed));
              };
              worker.postMessage({
                type: "INIT_FOLDER_TASK",
                key: key,
                rootPrefix: rootName == null ? "" : String(rootName),
                fileCount: fileCount || 0
              });
              return key;
            });
          }

          function cleanupZip(key) {
            var session = zipSessions[key];
            if (!session) return;
            try { session.worker && session.worker.terminate(); } catch (_) {}
            delete zipSessions[key];
          }

          function postZip(key, message) {
            var session = zipSessions[key];
            if (!session) return Promise.resolve(false);
            message.key = key;
            session.worker.postMessage(message);
            return Promise.resolve(true);
          }

          function postZipChunk(key, fileKey, rawBytes, isLast) {
            var session = zipSessions[key];
            if (!session) return Promise.resolve(false);
            var bytes = normalizeIncomingBytes(rawBytes);
            var buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
            session.worker.postMessage({
              type: "FOLDER_FILE_CHUNK",
              key: key,
              filePath: fileKey,
              chunk: buffer,
              isLast: isLast === true
            }, [buffer]);
            return Promise.resolve(true);
          }

          function finalizeZip(key) {
            var session = zipSessions[key];
            if (!session) return Promise.resolve(false);
            session.worker.postMessage({ type: "FINALIZE_FOLDER_TASK", key: key });
            return session.done;
          }

          function abortZip(key, reason) {
            var session = zipSessions[key];
            if (!session) return true;
            try {
              session.worker.postMessage({ type: "ABORT_FOLDER_TASK", key: key, message: reason || AppStrings.ui_canceled });
            } catch (_) {}
            try {
              if (session.writable && typeof session.writable.abort === "function") {
                session.writable.abort();
              }
            } catch (_) {}
            cleanupZip(key);
            return true;
          }

          var api = {
            prepareFile: prepareFile,
            writeFileChunk: writeFileChunk,
            closeFile: closeFile,
            abortFile: abortFile,
            createZipSession: createZipSession,
            registerZipDirectory: function (key, relativePath) {
              return postZip(key, { type: "REGISTER_DIRECTORY", relativePath: relativePath || "" });
            },
            beginZipFile: function (key, relativePath) {
              return postZip(key, { type: "BEGIN_FILE_ENTRY", relativePath: relativePath || "", filePath: relativePath || "" });
            },
            writeZipFileChunk: postZipChunk,
            finalizeZip: finalizeZip,
            abortZip: abortZip
          };
          globalThis.__folderSpanWebRtcBrowserDownloadApi = api;
          return api;
        })()
        """
    )

    override fun isSecureContext(): Boolean =
        js(
            """
            (function () {
              var locationValue = globalThis.location || {};
              var protocol = String(locationValue.protocol || "").toLowerCase();
              var hostname = String(locationValue.hostname || "").toLowerCase();
              var local = hostname === "localhost" ||
                hostname === "127.0.0.1" ||
                hostname === "::1" ||
                hostname.endsWith(".localhost");
              return globalThis.isSecureContext === true &&
                (protocol === "https:" || (protocol === "http:" && local));
            })()
            """
        ) as Boolean

    override fun createFileWriter(fileName: String, mimeType: String?): IncomingFileChunkWriter =
        BrowserFileWriter(fileName, mimeType)

    override suspend fun createZipSession(fileName: String, rootName: String, fileCount: Int): WebRtcBrowserZipSession {
        val key = (api.createZipSession(fileName, rootName, fileCount) as Promise<String>).await()
        return BrowserZipSession(key)
    }

    private class BrowserFileWriter(
        private val fileName: String,
        private val mimeType: String?,
    ) : IncomingFileChunkWriter {
        private val key = "file-$fileName-${Random.nextLong()}"

        override suspend fun prepare(path: String, fileSize: Long): Result<Boolean> =
            runCatching {
                (api.prepareFile(key, fileName, mimeType ?: "") as Promise<Boolean>).await()
            }

        override suspend fun writeChunk(path: String, fileSize: Long, data: ByteArray, offset: Long): Result<Boolean> =
            runCatching {
                (api.writeFileChunk(key, fileName, mimeType ?: "", fileSize, offset, data.asDynamic()) as Promise<Boolean>)
                    .await()
            }

        override suspend fun commit(path: String, fileSize: Long): Result<Boolean> =
            runCatching {
                (api.closeFile(key) as Promise<Boolean>).await()
            }

        override fun cleanup(path: String) {
            api.abortFile(key)
        }
    }

    private class BrowserZipSession(private val key: String) : WebRtcBrowserZipSession {
        override suspend fun registerDirectory(relativePath: String): Result<Boolean> =
            runCatching { (api.registerZipDirectory(key, relativePath) as Promise<Boolean>).await() }

        override suspend fun beginFile(relativePath: String): Result<Boolean> =
            runCatching { (api.beginZipFile(key, relativePath) as Promise<Boolean>).await() }

        override suspend fun writeFileChunk(fileKey: String, bytes: ByteArray, isLast: Boolean): Result<Boolean> =
            runCatching {
                (api.writeZipFileChunk(key, fileKey, bytes.asDynamic(), isLast) as Promise<Boolean>).await()
            }

        override suspend fun finalize(): Result<Boolean> =
            runCatching { (api.finalizeZip(key) as Promise<Boolean>).await() }

        override fun abort(reason: String) {
            api.abortZip(key, reason)
        }
    }
}
