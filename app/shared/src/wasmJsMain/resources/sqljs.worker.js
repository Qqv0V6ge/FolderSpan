/*
 * 本地实现兼容 SQLDelight web-worker-driver 的 SQL.js 消息协议。
 * 本轮对照的官方实现：@cashapp/sqldelight-sqljs-worker 2.4.0，
 * 固定提交 831eee11cd5c0fb092f62105223ae54a41b45e56。
 * 历史作者及实际参考/派生关系尚未证实；本说明不将整个本地文件归属上游。
 * 上游许可及本地行为差异说明见 third-party/workers/WORKER-NOTICES.txt。
 */
importScripts("sql-wasm.js");

const DB_NAME = "folderspan-sqljs";
const STORE_NAME = "databases";
const runtime = new URL(self.location.href).searchParams.get("runtime");
if (!runtime) throw new Error("Runtime namespace is required");
const DB_KEY = "runtime:" + new URL(runtime).origin;
let db;
let inTransaction = false;
let lastInsertRowId = 0;
let storageFailure;

const idbPromise = new Promise((resolve, reject) => {
  const request = indexedDB.open(DB_NAME, 1);
  request.onupgradeneeded = () => {
    if (!request.result.objectStoreNames.contains(STORE_NAME)) request.result.createObjectStore(STORE_NAME);
  };
  request.onsuccess = () => resolve(request.result);
  request.onerror = () => reject(request.error);
  request.onblocked = () => reject(new Error("Browser database upgrade blocked"));
});

async function loadDatabase() {
  const idb = await idbPromise;
  return new Promise((resolve, reject) => {
    const tx = idb.transaction(STORE_NAME, "readonly");
    const request = tx.objectStore(STORE_NAME).get(DB_KEY);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    tx.onabort = () => reject(tx.error);
  });
}

async function saveDatabase() {
  const idb = await idbPromise;
  const bytes = db.export();
  try {
    await new Promise((resolve, reject) => {
      const tx = idb.transaction(STORE_NAME, "readwrite");
      tx.oncomplete = resolve;
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error || new Error("Browser database save aborted"));
      tx.objectStore(STORE_NAME).put(bytes, DB_KEY);
    });
  } catch (error) {
    // A failed save must not later appear successful because another command flushed the same memory.
    storageFailure = error;
    throw error;
  }
}

const ready = (async () => {
  const SQL = await self.initSqlJs({ locateFile: () => "sql-wasm.wasm" });
  const saved = await loadDatabase();
  db = saved ? new SQL.Database(new Uint8Array(saved)) : new SQL.Database();
})();

async function execute(data) {
  await ready;
  if (storageFailure) throw storageFailure;
  let results;
  switch (data.action) {
    case "exec":
      if (!data.sql) throw new Error("exec: Missing query string");
      if (/^\s*SELECT\s+last_insert_rowid\(\)\s*;?\s*$/i.test(data.sql)) {
        return { columns: ["last_insert_rowid()"], values: [[lastInsertRowId]] };
      }
      results = db.exec(data.sql, data.params);
      // SQL.js export closes/reopens SQLite and resets this connection-local value.
      if (/^\s*(INSERT|REPLACE)\b/i.test(data.sql)) {
        lastInsertRowId = db.exec("SELECT last_insert_rowid()")[0].values[0][0];
      }
      if (!inTransaction && !/^\s*(select\b|pragma\s+user_version\s*$)/i.test(data.sql)) await saveDatabase();
      return results[0] ?? { values: [] };
    case "begin_transaction":
      results = db.exec("BEGIN TRANSACTION;");
      inTransaction = true;
      return results;
    case "end_transaction":
      results = db.exec("END TRANSACTION;");
      inTransaction = false;
      await saveDatabase();
      return results;
    case "rollback_transaction":
      results = db.exec("ROLLBACK TRANSACTION;");
      inTransaction = false;
      return results;
    default:
      throw new Error(`Unsupported action: ${data.action}`);
  }
}

let queue = Promise.resolve();
self.onmessage = ({ data }) => {
  queue = queue.then(async () => {
    try { postMessage({ id: data.id, results: await execute(data) }); }
    catch (error) { postMessage({ id: data.id, error: String(error && error.message || error) }); }
  });
};
