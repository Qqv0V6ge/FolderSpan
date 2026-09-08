@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.utils

import strings.AppStrings

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

internal object WebFileSystemApiStore {
    private val scope = MainScope()
    private val bytesCache = mutableMapOf<String, ByteArray>()
    private val loadingPaths = mutableSetOf<String>()
    private val opfsPersistJobs = mutableMapOf<String, Job>()
    private val localPersistJobs = mutableMapOf<String, Job>()

    init {
        ensureOpfsApi()
    }

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
                ensureOpfsApi()
                val resultAny = opfsReadBase64(normalized).await()
                val result = resultAny?.toString()
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
                ensureOpfsApi()
                val base64 = Base64.encode(snapshot)
                opfsWriteBase64(normalized, base64).await()
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
                ensureOpfsApi()
                opfsDeleteEntry(normalized).await()
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
        ensureOpfsApi()
        opfsDeleteEntry(normalized).await()
    }

    fun ensureDirectory(path: String) {
        val normalized = normalizePath(path)
        scope.launch {
            try {
                ensureOpfsApi()
                opfsEnsureDirectory(normalized).await()
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
                ensureOpfsApi()
                val base64 = Base64.encode(data)
                opfsWriteLocalFile(
                    path = normalized,
                    base64 = base64,
                    fileSize = fileSize,
                    offset = offset
                ).await()
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

@JsFun(
    """
    () => {
      if (globalThis.__folderSpanOpfsApi) {
        return true;
      }

      const normalizePath = (raw) => {
        const value = String(raw ?? "").trim().replace(/\\/g, "/");
        if (!value || value === "/") return "/";
        let normalized = value.startsWith("/") ? value : "/" + value;
        normalized = normalized.replace(/\/{2,}/g, "/");
        if (normalized.length > 1 && normalized.endsWith("/")) {
          normalized = normalized.slice(0, -1);
        }
        return normalized;
      };

      const splitPath = (raw) => {
        const normalized = normalizePath(raw);
        if (normalized === "/") {
          return { dirs: [], name: "" };
        }
        const parts = normalized.split("/").filter(Boolean);
        const name = parts.pop() || "";
        return { dirs: parts, name };
      };

      const ensureDir = async (root, dirs, create) => {
        let current = root;
        for (const segment of dirs) {
          current = await current.getDirectoryHandle(segment, { create });
        }
        return current;
      };

      const base64ToBytes = (base64) => {
        if (!base64) return new Uint8Array();
        const binary = atob(base64);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i += 1) {
          bytes[i] = binary.charCodeAt(i);
        }
        return bytes;
      };

      const bytesToBase64 = (bytes) => {
        if (!bytes || bytes.length === 0) return "";
        let binary = "";
        const chunkSize = 0x8000;
        for (let i = 0; i < bytes.length; i += chunkSize) {
          const chunk = bytes.subarray(i, i + chunkSize);
          binary += String.fromCharCode.apply(null, chunk);
        }
        return btoa(binary);
      };

      const localFileHandles = {};
      let localFileWorker = null;
      let localFileWorkerUrl = null;
      let localFileWorkerSeq = 1;
      const localFileWorkerRequests = {};
      let localFileWorkerHasHandle = {};

      const resolveSuggestedName = (normalizedPath) => {
        const split = splitPath(normalizedPath);
        return split.name || "web-file-api-test.txt";
      };

      const pickLocalFileHandle = async (key, suggestedName) => {
        if (localFileHandles[key]) {
          return localFileHandles[key];
        }
        if (typeof globalThis.showSaveFilePicker !== "function") {
          throw new Error("NotSupported");
        }
        const handle = await globalThis.showSaveFilePicker({
          suggestedName,
          types: [
            {
              description: "Binary Files",
              accept: {
                "application/octet-stream": [".bin", ".dat", ".tmp", ".txt"]
              }
            }
          ]
        });
        localFileHandles[key] = handle;
        return handle;
      };

      const localFileWorkerEntrypoint = () => {
        const localFileHandles = {};
        const localFileWriters = {};
        const localFileWriteQueues = {};
        const localFileCommitTimers = {};
        const localFileCommitTokens = {};
        const localFileTargetSizes = {};

        const normalizeIncomingBytes = (raw) => {
          if (!raw) return new Uint8Array(0);
          if (raw instanceof Uint8Array) return raw;
          if (raw instanceof ArrayBuffer) return new Uint8Array(raw);
          if (raw.buffer && raw.byteLength != null) {
            try {
              return new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength);
            } catch (_) {}
          }
          if (typeof raw === "string") {
            const binary = atob(raw);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i += 1) {
              bytes[i] = binary.charCodeAt(i);
            }
            return bytes;
          }
          if (Array.isArray(raw)) {
            return new Uint8Array(raw);
          }
          return new Uint8Array(0);
        };

        const cleanupLocalFileState = (key) => {
          if (localFileCommitTimers[key]) {
            clearTimeout(localFileCommitTimers[key]);
            delete localFileCommitTimers[key];
          }
          delete localFileCommitTokens[key];
          delete localFileWriters[key];
          delete localFileTargetSizes[key];
        };

        const enqueueLocalWrite = (key, task) => {
          const tail = localFileWriteQueues[key] || Promise.resolve();
          const next = tail.then(task, task);
          localFileWriteQueues[key] = next.catch(() => {});
          return next;
        };

        const closeLocalWriterNow = (key) => enqueueLocalWrite(key, async () => {
          const writer = localFileWriters[key];
          cleanupLocalFileState(key);
          if (!writer) return true;
          try {
            await writer.close();
            return true;
          } catch (_) {
            return false;
          }
        });

        const scheduleLocalCommit = (key) => {
          if (localFileCommitTimers[key]) {
            clearTimeout(localFileCommitTimers[key]);
          }
          const token = String(Date.now()) + "_" + String(Math.random());
          localFileCommitTokens[key] = token;
          localFileCommitTimers[key] = setTimeout(() => {
            if (localFileCommitTokens[key] !== token) return;
            enqueueLocalWrite(key, async () => {
              if (localFileCommitTokens[key] !== token) return true;
              const writer = localFileWriters[key];
              cleanupLocalFileState(key);
              if (!writer) return true;
              try {
                await writer.close();
                return true;
              } catch (_) {
                return false;
              }
            });
          }, 300);
        };

        const normalizeNumber = (value, fallback) => {
          let numberValue = Number(value);
          if (!isFinite(numberValue) || numberValue < 0) {
            numberValue = fallback;
          }
          return Math.floor(numberValue);
        };

        const writeLocalFile = async (data) => {
          const key = String(data.key || "");
          if (!key || key === "/") return false;
          if (data.handle) {
            localFileHandles[key] = data.handle;
          }
          const handle = localFileHandles[key];
          if (!handle) {
            throw new Error("MissingFileHandle");
          }
          const bytes = normalizeIncomingBytes(data.bytes ?? data.base64);
          const safeSize = normalizeNumber(data.fileSize, bytes.length);
          const safeOffset = normalizeNumber(data.offset, 0);
          try {
            return await enqueueLocalWrite(key, async () => {
              let writer = localFileWriters[key];
              const targetSize = Math.max(safeSize, safeOffset + bytes.length);
              if (!writer) {
                writer = await handle.createWritable({ keepExistingData: true });
                localFileWriters[key] = writer;
                try {
                  const file = await handle.getFile();
                  localFileTargetSizes[key] = normalizeNumber(file && file.size ? file.size : 0, 0);
                } catch (_) {
                  localFileTargetSizes[key] = 0;
                }
              }

              const knownSize = normalizeNumber(localFileTargetSizes[key] || 0, 0);
              const nextSize = Math.max(knownSize, targetSize);
              if (nextSize !== knownSize) {
                await writer.truncate(nextSize);
                localFileTargetSizes[key] = nextSize;
              }

              await writer.write({
                type: "write",
                position: safeOffset,
                data: bytes
              });
              const isTailChunk = safeOffset + bytes.length >= safeSize;
              if (!isTailChunk) {
                scheduleLocalCommit(key);
                return true;
              }
              try {
                await writer.close();
                return true;
              } finally {
                cleanupLocalFileState(key);
              }
            });
          } catch (_) {
            try {
              await closeLocalWriterNow(key);
            } catch (_) {}
            return false;
          }
        };

        const releaseLocalFile = async (key) => {
          const normalized = String(key || "");
          if (!normalized) return true;
          try {
            await closeLocalWriterNow(normalized);
          } catch (_) {}
          delete localFileHandles[normalized];
          delete localFileWriteQueues[normalized];
          return true;
        };

        self.onmessage = async (event) => {
          const data = event && event.data ? event.data : {};
          const id = Number(data.id || 0);
          try {
            let ok = false;
            if (data.type === "WRITE_LOCAL_FILE") {
              ok = await writeLocalFile(data);
            } else if (data.type === "RELEASE_LOCAL_FILE") {
              ok = await releaseLocalFile(data.key);
            }
            self.postMessage({ id: id, ok: !!ok });
          } catch (error) {
            self.postMessage({
              id: id,
              ok: false,
              error: error && error.message ? String(error.message) : "WorkerOperationFailed"
            });
          }
        };
      };

      const buildLocalFileWorkerSource = () => "(" + localFileWorkerEntrypoint.toString() + ")();";

      const resetLocalFileWorker = () => {
        if (localFileWorker) {
          try { localFileWorker.terminate(); } catch (_) {}
        }
        if (localFileWorkerUrl) {
          try { URL.revokeObjectURL(localFileWorkerUrl); } catch (_) {}
        }
        localFileWorker = null;
        localFileWorkerUrl = null;
        localFileWorkerHasHandle = {};
      };

      const ensureLocalFileWorker = () => {
        if (localFileWorker) {
          return localFileWorker;
        }
        const blob = new Blob([buildLocalFileWorkerSource()], { type: "text/javascript" });
        localFileWorkerUrl = URL.createObjectURL(blob);
        localFileWorker = new Worker(localFileWorkerUrl);
        localFileWorker.onmessage = (event) => {
          const data = event && event.data ? event.data : {};
          const id = Number(data.id || 0);
          const callback = localFileWorkerRequests[id];
          if (!callback) return;
          delete localFileWorkerRequests[id];
          if (data.ok === true) {
            callback.resolve(true);
          } else {
            callback.reject(new Error(data && data.error ? String(data.error) : "WorkerWriteFailed"));
          }
        };
        localFileWorker.onerror = (event) => {
          const message = event && event.message ? String(event.message) : "WorkerError";
          const keys = Object.keys(localFileWorkerRequests);
          for (const key of keys) {
            const callback = localFileWorkerRequests[key];
            delete localFileWorkerRequests[key];
            if (callback) {
              callback.reject(new Error(message));
            }
          }
          resetLocalFileWorker();
        };
        return localFileWorker;
      };

      const callLocalFileWorker = (message) => {
        return new Promise((resolve, reject) => {
          const worker = ensureLocalFileWorker();
          const id = localFileWorkerSeq++;
          localFileWorkerRequests[id] = { resolve, reject };
          try {
            worker.postMessage({ id, ...message });
          } catch (error) {
            delete localFileWorkerRequests[id];
            reject(error);
          }
        });
      };

      const releaseLocalFileWorkerState = (key) => {
        delete localFileWorkerHasHandle[key];
        if (!localFileWorker) return;
        callLocalFileWorker({ type: "RELEASE_LOCAL_FILE", key }).catch(() => {});
      };

      const apiUnavailable = () => !(globalThis.navigator && navigator.storage && navigator.storage.getDirectory);

      globalThis.__folderSpanOpfsApi = {
        async writeBase64(path, base64) {
          if (apiUnavailable()) return false;
          const normalized = normalizePath(path);
          if (normalized === "/") return false;
          const root = await navigator.storage.getDirectory();
          const split = splitPath(normalized);
          if (!split.name) return false;
          const dir = await ensureDir(root, split.dirs, true);
          const fileHandle = await dir.getFileHandle(split.name, { create: true });
          const writer = await fileHandle.createWritable();
          try {
            await writer.write(base64ToBytes(base64));
          } finally {
            await writer.close();
          }
          return true;
        },

        async readBase64(path) {
          if (apiUnavailable()) return null;
          const normalized = normalizePath(path);
          if (normalized === "/") return null;
          const root = await navigator.storage.getDirectory();
          const split = splitPath(normalized);
          if (!split.name) return null;
          try {
            const dir = await ensureDir(root, split.dirs, false);
            const fileHandle = await dir.getFileHandle(split.name);
            const file = await fileHandle.getFile();
            const buffer = await file.arrayBuffer();
            return bytesToBase64(new Uint8Array(buffer));
          } catch (error) {
            return null;
          }
        },

        async deleteEntry(path) {
          if (apiUnavailable()) return false;
          const normalized = normalizePath(path);
          if (normalized === "/") return false;
          releaseLocalFileWorkerState(normalized);
          const root = await navigator.storage.getDirectory();
          const split = splitPath(normalized);
          if (!split.name) return false;
          try {
            const dir = await ensureDir(root, split.dirs, false);
            await dir.removeEntry(split.name, { recursive: true });
            return true;
          } catch (error) {
            return false;
          }
        },

        async ensureDirectory(path) {
          if (apiUnavailable()) return false;
          const normalized = normalizePath(path);
          const root = await navigator.storage.getDirectory();
          const dirs = normalized === "/" ? [] : normalized.split("/").filter(Boolean);
          try {
            await ensureDir(root, dirs, true);
            return true;
          } catch (error) {
            return false;
          }
        },

        async writeLocalFile(path, base64, fileSize, offset) {
          const normalized = normalizePath(path);
          if (normalized === "/") return false;
          const safeSize = Math.max(0, Math.floor(Number(fileSize) || 0));
          const safeOffset = Math.max(0, Math.floor(Number(offset) || 0));
          const suggestedName = resolveSuggestedName(normalized);
          try {
            const fileHandle = await pickLocalFileHandle(normalized, suggestedName);
            const dispatch = (forceHandle) => {
              const payload = {
                type: "WRITE_LOCAL_FILE",
                key: normalized,
                base64,
                fileSize: safeSize,
                offset: safeOffset
              };
              if (forceHandle || !localFileWorkerHasHandle[normalized]) {
                payload.handle = fileHandle;
              }
              return callLocalFileWorker(payload).then(() => {
                localFileWorkerHasHandle[normalized] = true;
                return true;
              });
            };
            return await dispatch(false).catch(async () => {
              localFileWorkerHasHandle[normalized] = false;
              return await dispatch(true).catch(() => false);
            });
          } catch (error) {
            return false;
          }
        }
      };

      return true;
    }
    """
)
private external fun ensureOpfsApi(): Boolean

@JsFun(
    """
    (path, base64) => {
      const api = globalThis.__folderSpanOpfsApi;
      if (!api) return Promise.resolve(false);
      return api.writeBase64(path, base64);
    }
    """
)
private external fun opfsWriteBase64(path: String, base64: String): Promise<JsAny?>

@JsFun(
    """
    (path) => {
      const api = globalThis.__folderSpanOpfsApi;
      if (!api) return Promise.resolve(null);
      return api.readBase64(path);
    }
    """
)
private external fun opfsReadBase64(path: String): Promise<JsAny?>

@JsFun(
    """
    (path) => {
      const api = globalThis.__folderSpanOpfsApi;
      if (!api) return Promise.resolve(false);
      return api.deleteEntry(path);
    }
    """
)
private external fun opfsDeleteEntry(path: String): Promise<JsAny?>

@JsFun(
    """
    (path) => {
      const api = globalThis.__folderSpanOpfsApi;
      if (!api) return Promise.resolve(false);
      return api.ensureDirectory(path);
    }
    """
)
private external fun opfsEnsureDirectory(path: String): Promise<JsAny?>

@JsFun(
    """
    (path, base64, fileSize, offset) => {
      const api = globalThis.__folderSpanOpfsApi;
      if (!api) return Promise.resolve(false);
      return api.writeLocalFile(path, base64, fileSize, offset);
    }
    """
)
private external fun opfsWriteLocalFile(
    path: String,
    base64: String,
    fileSize: Long,
    offset: Long,
): Promise<JsAny?>
