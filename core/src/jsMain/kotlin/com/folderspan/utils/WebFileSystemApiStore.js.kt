package com.folderspan.utils

import strings.AppStrings

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlin.js.unsafeCast

internal object WebFileSystemApiStore {
    private val scope = MainScope()
    private val bytesCache = mutableMapOf<String, ByteArray>()
    private val loadingPaths = mutableSetOf<String>()
    private val opfsPersistJobs = mutableMapOf<String, Job>()
    private val localPersistJobs = mutableMapOf<String, Job>()

    private val opfsApi: dynamic = js(
        """
        (function () {
          function normalizePath(raw) {
            var value = String(raw == null ? "" : raw).trim().replace(/\\/g, "/");
            if (!value || value === "/") return "/";
            var normalized = value.charAt(0) === "/" ? value : "/" + value;
            normalized = normalized.replace(/\/{2,}/g, "/");
            if (normalized.length > 1 && normalized.charAt(normalized.length - 1) === "/") {
              normalized = normalized.substring(0, normalized.length - 1);
            }
            return normalized;
          }

          function splitPath(raw) {
            var normalized = normalizePath(raw);
            if (normalized === "/") return { dirs: [], name: "" };
            var parts = normalized.split("/").filter(function (item) { return !!item; });
            var name = parts.pop() || "";
            return { dirs: parts, name: name };
          }

          function ensureDir(root, dirs, create) {
            var current = Promise.resolve(root);
            for (var i = 0; i < dirs.length; i += 1) {
              (function (segment) {
                current = current.then(function (dir) {
                  return dir.getDirectoryHandle(segment, { create: create });
                });
              })(dirs[i]);
            }
            return current;
          }

          function base64ToBytes(base64) {
            if (!base64) return new Uint8Array();
            var binary = atob(base64);
            var bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i += 1) {
              bytes[i] = binary.charCodeAt(i);
            }
            return bytes;
          }

          function bytesToBase64(bytes) {
            if (!bytes || bytes.length === 0) return "";
            var binary = "";
            var chunkSize = 0x8000;
            for (var i = 0; i < bytes.length; i += chunkSize) {
              var chunk = bytes.subarray(i, i + chunkSize);
              binary += String.fromCharCode.apply(null, chunk);
            }
            return btoa(binary);
          }

          function apiUnavailable() {
            return !(globalThis.navigator && navigator.storage && navigator.storage.getDirectory);
          }

          function withRoot() {
            return Promise.resolve().then(function () {
              return navigator.storage.getDirectory();
            });
          }

          var localFileHandles = {};
          var localFileWorker = null;
          var localFileWorkerUrl = null;
          var localFileWorkerSeq = 1;
          var localFileWorkerRequests = {};
          var localFileWorkerHasHandle = {};

          function resolveSuggestedName(normalizedPath) {
            var split = splitPath(normalizedPath);
            return split.name || "web-file-api-test.txt";
          }

          function pickLocalFileHandle(key, suggestedName) {
            if (localFileHandles[key]) {
              return Promise.resolve(localFileHandles[key]);
            }
            var picker = globalThis.showSaveFilePicker;
            if (typeof picker !== "function") {
              return Promise.reject(new Error("NotSupported"));
            }
            return Promise.resolve(
              picker({
                suggestedName: suggestedName,
                types: [
                  {
                    description: "Binary Files",
                    accept: {
                      "application/octet-stream": [".bin", ".dat", ".tmp", ".txt"]
                    }
                  }
                ]
              })
            ).then(function (handle) {
              localFileHandles[key] = handle;
              return handle;
            });
          }

          function normalizeTransferBytes(raw) {
            if (!raw) return new Uint8Array(0);
            if (raw instanceof Uint8Array) return raw;
            if (raw instanceof ArrayBuffer) return new Uint8Array(raw);
            if (raw.buffer && raw.byteLength != null) {
              try {
                return new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength);
              } catch (_) {}
            }
            if (Array.isArray(raw)) {
              return new Uint8Array(raw);
            }
            return new Uint8Array(0);
          }

          function localFileWorkerEntrypoint() {
            var localFileHandles = {};
            var localFileWriters = {};
            var localFileWriteQueues = {};
            var localFileCommitTimers = {};
            var localFileCommitTokens = {};
            var localFileTargetSizes = {};

            function normalizeIncomingBytes(raw) {
              if (!raw) return new Uint8Array(0);
              if (raw instanceof Uint8Array) return raw;
              if (raw instanceof ArrayBuffer) return new Uint8Array(raw);
              if (raw.buffer && raw.byteLength != null) {
                try {
                  return new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength);
                } catch (_) {}
              }
              if (typeof raw === "string") {
                var binary = atob(raw);
                var bytes = new Uint8Array(binary.length);
                for (var i = 0; i < binary.length; i += 1) {
                  bytes[i] = binary.charCodeAt(i);
                }
                return bytes;
              }
              if (Array.isArray(raw)) {
                return new Uint8Array(raw);
              }
              return new Uint8Array(0);
            }

            function cleanupLocalFileState(key) {
              if (localFileCommitTimers[key]) {
                clearTimeout(localFileCommitTimers[key]);
                delete localFileCommitTimers[key];
              }
              delete localFileCommitTokens[key];
              delete localFileWriters[key];
              delete localFileTargetSizes[key];
            }

            function enqueueLocalWrite(key, task) {
              var tail = localFileWriteQueues[key] || Promise.resolve();
              var next = tail.then(task, task);
              localFileWriteQueues[key] = next.then(function () {}, function () {});
              return next;
            }

            function closeLocalWriterNow(key) {
              return enqueueLocalWrite(key, function () {
                var writer = localFileWriters[key];
                cleanupLocalFileState(key);
                if (!writer) return true;
                return Promise.resolve(writer.close()).then(
                  function () { return true; },
                  function () { return false; }
                );
              });
            }

            function scheduleLocalCommit(key) {
              if (localFileCommitTimers[key]) {
                clearTimeout(localFileCommitTimers[key]);
              }
              var token = String(Date.now()) + "_" + String(Math.random());
              localFileCommitTokens[key] = token;
              localFileCommitTimers[key] = setTimeout(function () {
                if (localFileCommitTokens[key] !== token) return;
                enqueueLocalWrite(key, function () {
                  if (localFileCommitTokens[key] !== token) return true;
                  var writer = localFileWriters[key];
                  cleanupLocalFileState(key);
                  if (!writer) return true;
                  return Promise.resolve(writer.close()).then(
                    function () { return true; },
                    function () { return false; }
                  );
                });
              }, 300);
            }

            function normalizeNumber(value, fallback) {
              var numberValue = Number(value);
              if (!isFinite(numberValue) || numberValue < 0) {
                numberValue = fallback;
              }
              return Math.floor(numberValue);
            }

            function writeLocalFile(data) {
              var key = String(data.key || "");
              if (!key || key === "/") return Promise.resolve(false);
              if (data.handle) {
                localFileHandles[key] = data.handle;
              }
              var handle = localFileHandles[key];
              if (!handle) {
                return Promise.reject(new Error("MissingFileHandle"));
              }
              var bytes = normalizeIncomingBytes(data.bytes != null ? data.bytes : data.base64);
              var safeSize = normalizeNumber(data.fileSize, bytes.length);
              var safeOffset = normalizeNumber(data.offset, 0);
              return enqueueLocalWrite(key, function () {
                var writer = localFileWriters[key];
                var targetSize = Math.max(safeSize, safeOffset + bytes.length);
                var prepare = Promise.resolve();
                if (!writer) {
                  prepare = Promise.resolve(handle.createWritable({ keepExistingData: true }))
                    .then(function (createdWriter) {
                      writer = createdWriter;
                      localFileWriters[key] = createdWriter;
                      return Promise.resolve(handle.getFile()).then(
                        function (file) {
                          localFileTargetSizes[key] = normalizeNumber(file && file.size ? file.size : 0, 0);
                        },
                        function () {
                          localFileTargetSizes[key] = 0;
                        }
                      );
                    });
                }
                return prepare
                  .then(function () {
                    var knownSize = normalizeNumber(localFileTargetSizes[key] || 0, 0);
                    var nextSize = Math.max(knownSize, targetSize);
                    var resize = Promise.resolve();
                    if (nextSize !== knownSize) {
                      resize = Promise.resolve(writer.truncate(nextSize)).then(function () {
                        localFileTargetSizes[key] = nextSize;
                      });
                    }
                    return resize.then(function () {
                      return Promise.resolve(writer.write({
                        type: "write",
                        position: safeOffset,
                        data: bytes
                      }));
                    }).then(function () {
                      var isTailChunk = safeOffset + bytes.length >= safeSize;
                      if (!isTailChunk) {
                        scheduleLocalCommit(key);
                        return true;
                      }
                      return Promise.resolve(writer.close()).then(
                        function () {
                          cleanupLocalFileState(key);
                          return true;
                        },
                        function (error) {
                          cleanupLocalFileState(key);
                          throw error;
                        }
                      );
                    });
                  });
              }).then(
                function () { return true; },
                function () {
                  return Promise.resolve(closeLocalWriterNow(key)).then(
                    function () { return false; },
                    function () { return false; }
                  );
                }
              );
            }

            function releaseLocalFile(key) {
              var normalized = String(key || "");
              if (!normalized) return Promise.resolve(true);
              return Promise.resolve(closeLocalWriterNow(normalized)).then(
                function () {
                  delete localFileHandles[normalized];
                  delete localFileWriteQueues[normalized];
                  return true;
                },
                function () {
                  delete localFileHandles[normalized];
                  delete localFileWriteQueues[normalized];
                  return true;
                }
              );
            }

            self.onmessage = function (event) {
              var data = event && event.data ? event.data : {};
              var id = Number(data.id || 0);
              var operation;
              if (data.type === "WRITE_LOCAL_FILE") {
                operation = writeLocalFile(data);
              } else if (data.type === "RELEASE_LOCAL_FILE") {
                operation = releaseLocalFile(data.key);
              } else {
                operation = Promise.resolve(false);
              }
              Promise.resolve(operation).then(
                function (ok) {
                  self.postMessage({ id: id, ok: !!ok });
                },
                function (error) {
                  self.postMessage({
                    id: id,
                    ok: false,
                    error: error && error.message ? String(error.message) : "WorkerOperationFailed"
                  });
                }
              );
            };
          }

          function buildLocalFileWorkerSource() {
            return "(" + localFileWorkerEntrypoint.toString() + ")();";
          }

          function resetLocalFileWorker() {
            if (localFileWorker) {
              try { localFileWorker.terminate(); } catch (_) {}
            }
            if (localFileWorkerUrl) {
              try { URL.revokeObjectURL(localFileWorkerUrl); } catch (_) {}
            }
            localFileWorker = null;
            localFileWorkerUrl = null;
            localFileWorkerHasHandle = {};
          }

          function ensureLocalFileWorker() {
            if (localFileWorker) {
              return localFileWorker;
            }
            var blob = new Blob([buildLocalFileWorkerSource()], { type: "text/javascript" });
            localFileWorkerUrl = URL.createObjectURL(blob);
            localFileWorker = new Worker(localFileWorkerUrl);
            localFileWorker.onmessage = function (event) {
              var data = event && event.data ? event.data : {};
              var id = Number(data.id || 0);
              var callback = localFileWorkerRequests[id];
              if (!callback) return;
              delete localFileWorkerRequests[id];
              if (data.ok === true) {
                callback.resolve(true);
              } else {
                callback.reject(new Error(data && data.error ? String(data.error) : "WorkerWriteFailed"));
              }
            };
            localFileWorker.onerror = function (event) {
              var message = event && event.message ? String(event.message) : "WorkerError";
              var keys = Object.keys(localFileWorkerRequests);
              for (var i = 0; i < keys.length; i += 1) {
                var callback = localFileWorkerRequests[keys[i]];
                delete localFileWorkerRequests[keys[i]];
                if (callback) {
                  callback.reject(new Error(message));
                }
              }
              resetLocalFileWorker();
            };
            return localFileWorker;
          }

          function callLocalFileWorker(message) {
            return new Promise(function (resolve, reject) {
              var worker = ensureLocalFileWorker();
              var id = localFileWorkerSeq++;
              localFileWorkerRequests[id] = { resolve: resolve, reject: reject };
              try {
                worker.postMessage(Object.assign({ id: id }, message));
              } catch (error) {
                delete localFileWorkerRequests[id];
                reject(error);
              }
            });
          }

          function releaseLocalFileWorkerState(key) {
            delete localFileWorkerHasHandle[key];
            if (!localFileWorker) return;
            callLocalFileWorker({ type: "RELEASE_LOCAL_FILE", key: key }).catch(function () {});
          }

          return {
            writeBase64: function (path, base64) {
              if (apiUnavailable()) return Promise.resolve(false);
              var normalized = normalizePath(path);
              if (normalized === "/") return Promise.resolve(false);
              var split = splitPath(normalized);
              if (!split.name) return Promise.resolve(false);
              return withRoot()
                .then(function (root) { return ensureDir(root, split.dirs, true); })
                .then(function (dir) { return dir.getFileHandle(split.name, { create: true }); })
                .then(function (fileHandle) { return fileHandle.createWritable(); })
                .then(function (writer) {
                  var bytes = base64ToBytes(base64);
                  return Promise.resolve(writer.write(bytes))
                    .then(function () { return writer.close(); })
                    .then(function () { return true; });
                })
                .catch(function () { return false; });
            },

            readBase64: function (path) {
              if (apiUnavailable()) return Promise.resolve(null);
              var normalized = normalizePath(path);
              if (normalized === "/") return Promise.resolve(null);
              var split = splitPath(normalized);
              if (!split.name) return Promise.resolve(null);
              return withRoot()
                .then(function (root) { return ensureDir(root, split.dirs, false); })
                .then(function (dir) { return dir.getFileHandle(split.name); })
                .then(function (fileHandle) { return fileHandle.getFile(); })
                .then(function (file) { return file.arrayBuffer(); })
                .then(function (buffer) { return bytesToBase64(new Uint8Array(buffer)); })
                .catch(function () { return null; });
            },

            deleteEntry: function (path) {
              if (apiUnavailable()) return Promise.resolve(false);
              var normalized = normalizePath(path);
              if (normalized === "/") return Promise.resolve(false);
              releaseLocalFileWorkerState(normalized);
              var split = splitPath(normalized);
              if (!split.name) return Promise.resolve(false);
              return withRoot()
                .then(function (root) { return ensureDir(root, split.dirs, false); })
                .then(function (dir) { return dir.removeEntry(split.name, { recursive: true }); })
                .then(function () { return true; })
                .catch(function () { return false; });
            },

            ensureDirectory: function (path) {
              if (apiUnavailable()) return Promise.resolve(false);
              var normalized = normalizePath(path);
              var dirs = normalized === "/" ? [] : normalized.split("/").filter(function (item) { return !!item; });
              return withRoot()
                .then(function (root) { return ensureDir(root, dirs, true); })
                .then(function () { return true; })
                .catch(function () { return false; });
            },

            writeLocalFile: function (path, bytes, fileSize, offset) {
              var normalized = normalizePath(path);
              if (normalized === "/") return Promise.resolve(false);
              var payloadBytes = normalizeTransferBytes(bytes);
              var safeSize = Number(fileSize);
              if (!isFinite(safeSize) || safeSize < 0) {
                safeSize = 0;
              }
              safeSize = Math.floor(safeSize);
              var safeOffset = Number(offset);
              if (!isFinite(safeOffset) || safeOffset < 0) {
                safeOffset = 0;
              }
              safeOffset = Math.floor(safeOffset);
              var suggestedName = resolveSuggestedName(normalized);

              return pickLocalFileHandle(normalized, suggestedName)
                .then(function (fileHandle) {
                  var dispatch = function (forceHandle) {
                    var payload = {
                      type: "WRITE_LOCAL_FILE",
                      key: normalized,
                      bytes: payloadBytes,
                      fileSize: safeSize,
                      offset: safeOffset
                    };
                    if (forceHandle || !localFileWorkerHasHandle[normalized]) {
                      payload.handle = fileHandle;
                    }
                    return callLocalFileWorker(payload).then(function () {
                      localFileWorkerHasHandle[normalized] = true;
                      return true;
                    });
                  };

                  return dispatch(false).catch(function () {
                    localFileWorkerHasHandle[normalized] = false;
                    return dispatch(true).catch(function () { return false; });
                  });
                })
                .catch(function () { return false; });
            }
          };
        })()
        """
    )

    fun readCached(path: String): ByteArray? {
        val normalized = normalizePath(path)
        return bytesCache[normalized]?.copyOf()
    }

    fun requestRead(path: String) {
        val normalized = normalizePath(path)
        if (normalized == "/" || bytesCache.containsKey(normalized) || loadingPaths.contains(normalized)) {
            return
        }
        loadingPaths.add(normalized)
        scope.launch {
            try {
                val result = opfsApi.readBase64(normalized).unsafeCast<Promise<String?>>().await()
                val decoded = result?.takeIf { item -> item.isNotEmpty() }?.let { item ->
                    runCatching { Base64.decode(item) }.getOrNull()
                } ?: if (result == "") byteArrayOf() else null
                if (decoded != null) {
                    bytesCache[normalized] = decoded
                }
            } catch (error: Throwable) {
                LogKit.e(AppStrings.ui_web_file_system_api_read_failed_arg0.format(arg0 = normalized), error)
            } finally {
                loadingPaths.remove(normalized)
            }
        }
    }

    fun cacheAndPersist(path: String, bytes: ByteArray) {
        val normalized = normalizePath(path)
        val snapshot = bytes.copyOf()
        bytesCache[normalized] = snapshot
        if (!shouldPersistWebFileSystemApiPath(normalized, PathUtils.getCachePath())) {
            return
        }
        val previous = opfsPersistJobs[normalized]
        val current = scope.launch {
            previous?.join()
            try {
                val base64 = Base64.encode(snapshot)
                opfsApi.writeBase64(normalized, base64).unsafeCast<Promise<Boolean>>().await()
            } catch (error: Throwable) {
                LogKit.e(AppStrings.ui_web_file_system_api_write_failed_arg0.format(arg0 = normalized), error)
            }
        }
        opfsPersistJobs[normalized] = current
        current.invokeOnCompletion {
            if (opfsPersistJobs[normalized] === current) {
                opfsPersistJobs.remove(normalized)
            }
        }
    }

    fun delete(path: String) {
        val normalized = normalizePath(path)
        bytesCache.keys.removeAll { item ->
            item == normalized || item.startsWith("$normalized/")
        }
        scope.launch {
            try {
                opfsApi.deleteEntry(normalized).unsafeCast<Promise<Boolean>>().await()
            } catch (error: Throwable) {
                LogKit.e(AppStrings.ui_web_file_system_api_deletion_failed_arg0.format(arg0 = normalized), error)
            }
        }
    }

    suspend fun deleteAndAwait(path: String) {
        val normalized = normalizePath(path)
        bytesCache.keys.removeAll { item ->
            item == normalized || item.startsWith("$normalized/")
        }
        opfsPersistJobs
            .filterKeys { item -> item == normalized || item.startsWith("$normalized/") }
            .values
            .forEach { job -> job.join() }
        opfsApi.deleteEntry(normalized).unsafeCast<Promise<Boolean>>().await()
    }

    fun ensureDirectory(path: String) {
        val normalized = normalizePath(path)
        scope.launch {
            try {
                opfsApi.ensureDirectory(normalized).unsafeCast<Promise<Boolean>>().await()
            } catch (error: Throwable) {
                LogKit.e(AppStrings.ui_web_file_system_api_failed_create_directory_arg0.format(arg0 = normalized), error)
            }
        }
    }

    fun rename(basePath: String, oldName: String, newName: String) {
        val normalizedBase = normalizePath(basePath)
        val oldPath = normalizePath("$normalizedBase/$oldName")
        val newPath = normalizePath("$normalizedBase/$newName")
        val cached = bytesCache.remove(oldPath)
        if (cached != null) {
            cacheAndPersist(newPath, cached)
        }
        delete(oldPath)
    }

    suspend fun persistLocalFile(path: String, fileSize: Long, data: ByteArray, offset: Long) {
        if (fileSize < 0L || offset < 0L) return
        val normalized = normalizePath(path)
        if (normalized == "/") return
        val dataSize = data.size.toLong()
        val isTailChunk = fileSize == 0L || offset + dataSize >= fileSize
        val previous = localPersistJobs[normalized]
        val current = scope.launch {
            previous?.join()
            try {
                opfsApi
                    .writeLocalFile(normalized, data, fileSize, offset)
                    .unsafeCast<Promise<Boolean>>()
                    .await()
            } catch (error: Throwable) {
                LogKit.e(AppStrings.ui_web_local_file_api_write_failed_arg0.format(arg0 = normalized), error)
            }
        }
        localPersistJobs[normalized] = current
        current.invokeOnCompletion {
            if (localPersistJobs[normalized] === current) {
                localPersistJobs.remove(normalized)
            }
        }
        if (isTailChunk) {
            current.join()
        }
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        var normalized = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        normalized = normalized.replace(Regex("/{2,}"), "/")
        if (normalized.length > 1 && normalized.endsWith("/")) {
            normalized = normalized.dropLast(1)
        }
        return normalized
    }
}
