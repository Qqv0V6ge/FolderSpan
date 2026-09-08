importScripts("sql-wasm.js");

const DB_NAME = "folderspan-sqljs";
const STORE_NAME = "databases";
const DB_KEY = "main";

let db = null;
let idbPromise = null;
let saveTimer = null;

function getIdb() {
  if (!self.indexedDB) {
    return Promise.resolve(null);
  }
  if (idbPromise) {
    return idbPromise;
  }
  idbPromise = new Promise((resolve) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(STORE_NAME)) {
        database.createObjectStore(STORE_NAME);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
  });
  return idbPromise;
}

function toArrayBuffer(data) {
  if (!data) return null;
  if (data instanceof ArrayBuffer) return data;
  if (data.buffer instanceof ArrayBuffer) {
    return data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
  }
  return null;
}

async function loadDatabase() {
  const idb = await getIdb();
  if (!idb) return null;
  return new Promise((resolve) => {
    const tx = idb.transaction(STORE_NAME, "readonly");
    const store = tx.objectStore(STORE_NAME);
    const request = store.get(DB_KEY);
    request.onsuccess = () => resolve(request.result || null);
    request.onerror = () => resolve(null);
  });
}

async function saveDatabase(buffer) {
  const idb = await getIdb();
  if (!idb) return;
  return new Promise((resolve) => {
    const tx = idb.transaction(STORE_NAME, "readwrite");
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
    tx.onabort = () => resolve();
    const store = tx.objectStore(STORE_NAME);
    store.put(buffer, DB_KEY);
  });
}

function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    if (!db) return;
    try {
      const exportData = db.export();
      const buffer = toArrayBuffer(exportData);
      if (buffer) {
        saveDatabase(buffer);
      }
    } catch (_) {
    }
  }, 200);
}

async function createDatabase() {
  const SQL = await self.initSqlJs({
    locateFile: () => "sql-wasm.wasm"
  });
  const saved = await loadDatabase();
  if (saved) {
    db = new SQL.Database(new Uint8Array(saved));
  } else {
    db = new SQL.Database();
  }
}

function onModuleReady() {
  const data = this.data;

  switch (data && data.action) {
    case "exec": {
      if (!data.sql) {
        throw new Error("exec: Missing query string");
      }
      const results = db.exec(data.sql, data.params);
      const isWrite = !/^\s*select\b/i.test(data.sql);
      if (isWrite) {
        scheduleSave();
      }
      return postMessage({
        id: data.id,
        results: results[0] ?? { values: [] }
      });
    }
    case "begin_transaction":
      return postMessage({
        id: data.id,
        results: db.exec("BEGIN TRANSACTION;")
      });
    case "end_transaction": {
      const results = db.exec("END TRANSACTION;");
      scheduleSave();
      return postMessage({
        id: data.id,
        results
      });
    }
    case "rollback_transaction":
      return postMessage({
        id: data.id,
        results: db.exec("ROLLBACK TRANSACTION;")
      });
    default:
      throw new Error(`Unsupported action: ${data && data.action}`);
  }
}

function onError(err) {
  return postMessage({
    id: this.data.id,
    error: err
  });
}

if (typeof importScripts === "function") {
  db = null;
  const sqlModuleReady = createDatabase();
  self.onmessage = (event) => {
    return sqlModuleReady
      .then(onModuleReady.bind(event))
      .catch(onError.bind(event));
  };
}
