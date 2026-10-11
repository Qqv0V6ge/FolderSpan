#!/usr/bin/env node
// Tests the production SQL.js worker in Chromium, using only disposable browser storage.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');
const { chromium } = require(process.env.FOLDERSPAN_PLAYWRIGHT || 'playwright');

(async () => {
    const root = path.resolve('app/shared/src/jsMain/resources');
    const server = http.createServer(async (request, response) => {
        try {
            const url = new URL(request.url, 'http://localhost');
            if (url.pathname === '/') { response.end('<!doctype html><title>Worker verification</title>'); return; }
            const name = url.pathname.slice(1);
            assert.ok(['sqljs.worker.js', 'sql-wasm.js', 'sql-wasm.wasm'].includes(name));
            let body = await fs.readFile(path.join(root, name));
            if (name === 'sqljs.worker.js' && url.searchParams.has('quota')) {
                body = Buffer.concat([Buffer.from('IDBObjectStore.prototype.put = function(){throw new DOMException("test quota", "QuotaExceededError")};\n'), body]);
            }
            response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript');
            response.end(body);
        } catch { response.writeHead(404).end(); }
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    let browser;
    try {
        browser = await chromium.launch({ executablePath: process.env.FOLDERSPAN_CHROMIUM });
        const page = await browser.newPage();
        await page.goto(`http://127.0.0.1:${server.address().port}`);
        const result = await page.evaluate(async () => {
            const workers = [];
            function worker(runtime, extra = '') {
                const instance = new Worker(`/sqljs.worker.js?runtime=${encodeURIComponent(runtime)}${extra}`);
                workers.push(instance);
                let sequence = 0;
                const pending = new Map();
                instance.onmessage = ({ data }) => {
                    const callbacks = pending.get(data.id);
                    if (!callbacks) return;
                    pending.delete(data.id);
                    if (data.error) callbacks.reject(new Error(data.error)); else callbacks.resolve(data.results);
                };
                instance.onerror = error => { for (const item of pending.values()) item.reject(new Error(error.message)); pending.clear(); };
                return {
                    send(action, sql) {
                        const id = ++sequence;
                        return new Promise((resolve, reject) => {
                            const timer = setTimeout(() => reject(new Error('Worker timed out')), 10000);
                            pending.set(id, { resolve: value => { clearTimeout(timer); resolve(value); }, reject: error => { clearTimeout(timer); reject(error); } });
                            instance.postMessage({ id, action, sql });
                        });
                    },
                    close() { instance.terminate(); },
                };
            }
            try {
                let first = worker('https://runtime-a.example');
                await first.send('exec', 'CREATE TABLE records(id INTEGER PRIMARY KEY AUTOINCREMENT, value TEXT)');
                await first.send('exec', "INSERT INTO records(value) VALUES ('a')");
                const firstId = await first.send('exec', 'SELECT last_insert_rowid()');
                await first.send('begin_transaction');
                await first.send('exec', "INSERT INTO records(value) VALUES ('rolled-back')");
                await first.send('rollback_transaction');
                first.close();
                first = worker('https://runtime-a.example');
                const persisted = await first.send('exec', 'SELECT value FROM records');
                const second = worker('https://runtime-b.example');
                const other = await second.send('exec', "SELECT name FROM sqlite_master WHERE type='table'");
                const quota = worker('https://runtime-quota.example', '&quota=1');
                const rejected = [];
                for (const sql of ['CREATE TABLE bad(value TEXT)', "INSERT INTO bad VALUES ('lost')"]) {
                    try { await quota.send('exec', sql); rejected.push(false); } catch (error) { rejected.push(error.message.includes('test quota')); }
                }
                return { firstId: firstId.values[0][0], persisted: persisted.values, other: other.values, rejected };
            } finally { workers.forEach(worker => worker.terminate()); }
        });
        assert.equal(result.firstId, 1);
        assert.deepEqual(result.persisted, [['a']]);
        assert.deepEqual(result.other, []);
        assert.deepEqual(result.rejected, [true, true]);
        console.log(JSON.stringify({ passed: true, checks: ['generated ID survives export', 'reload persistence', 'rollback', 'Runtime isolation', 'quota error locks writes'] }));
    } finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
