#!/usr/bin/env node
// Real JS/Wasm pages, two disposable Runtimes, and a loopback WebDAV fixture.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');
const { chromium } = require(process.env.FOLDERSPAN_PLAYWRIGHT || 'playwright');
const { observePlatform } = require('./webui_transport.cjs');

async function until(check, message, timeout = 30000) {
    const deadline = Date.now() + timeout;
    while (!await check()) {
        assert.ok(Date.now() < deadline, message);
        await new Promise(resolve => setTimeout(resolve, 100));
    }
}
async function render(page) {
    await page.evaluate(() => new Promise(resolve => {
        let frames = 12;
        const tick = () => --frames ? requestAnimationFrame(tick) : resolve();
        requestAnimationFrame(tick);
    }));
}
async function click(page, x, y) { await page.bringToFront(); await page.mouse.click(x, y); await render(page); }
async function fill(page, x, y, value) {
    await click(page, x, y);
    await page.keyboard.press('Control+A');
    await render(page);
    await page.keyboard.insertText(value);
    await render(page);
}
async function savedNetworks(page, runtime) {
    return page.evaluate(runtime => new Promise((resolve, reject) => {
        const worker = new Worker('/sqljs.worker.js?runtime=' + encodeURIComponent(runtime));
        const timer = setTimeout(() => { worker.terminate(); reject(new Error('Database read timeout')); }, 10000);
        worker.onmessage = ({ data }) => {
            clearTimeout(timer); worker.terminate();
            if (data.error) reject(new Error(data.error)); else resolve(data.results.values);
        };
        worker.onerror = error => { clearTimeout(timer); worker.terminate(); reject(new Error(error.message)); };
        worker.postMessage({ id: 1, action: 'exec', sql: 'SELECT name FROM NetworkDrive ORDER BY name' });
    }), runtime);
}

async function main() {
    const fixtures = JSON.parse(await fs.readFile(process.argv[2], 'utf8'));
    const output = path.resolve(process.argv[3]);
    await fs.mkdir(output, { recursive: true });
    const checks = [], errors = [], sessions = [], pages = [], paths = [[], []], outbound = [];
    const fileRequests = [], fileResponses = [];
    const diagnostics = [];
    const management = [{ events: {}, requests: 0 }, { events: {}, requests: 0 }];
    const deviceBodyRequests = [];
    let deviceBound = false;
    let davRequests = 0;
    const dav = http.createServer(async (request, response) => {
        for await (const _ of request) { /* consume the PROPFIND body */ }
        if (request.method !== 'PROPFIND') { response.writeHead(405).end(); return; }
        davRequests++;
        response.writeHead(207, { 'Content-Type': 'application/xml' }).end(
            '<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">' +
            '<d:response><d:href>/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>' +
            '<d:response><d:href>/remote.txt</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>8</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>');
    });
    await new Promise(resolve => dav.listen(0, '127.0.0.1', resolve));
    let browser;
    try {
        browser = await chromium.launch({ executablePath: process.env.FOLDERSPAN_CHROMIUM, args: ['--enable-unsafe-swiftshader'] });
        for (let index = 0; index < 2; index++) {
            const runtime = fixtures.runtimes[index];
            const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'en-US' });
            await context.addInitScript(({ runtime, index }) => {
                sessionStorage.setItem('folderspan.webui.target', runtime.url);
                const prefix = `runtime:${runtime.url.length}:${runtime.url}:`;
                const defaults = {
                    'settings.app.onboarding.completed': 'true', 'settings.app.welcomeAgreement.accepted': 'true',
                    'settings.appearance.language': 'en', 'settings.drawer.expand.device': 'false',
                    'settings.fileShare.port': String(runtime.devicePort), 'settings.easyFileShare.port': String(runtime.sharePort),
                    'settings.easyFileShare.autoStart': String(index === 1),
                    'settings.easyFileShare.sharePaths': JSON.stringify([runtime.directory + '/home/alpha.txt']),
                    'settings.mcp.port': String(runtime.mcpPort),
                };
                for (const [key, value] of Object.entries(defaults)) {
                    if (localStorage.getItem(prefix + key) === null) localStorage.setItem(prefix + key, value);
                }
            }, { runtime, index });
            await context.addInitScript(allowed => {
                const NativeWebSocket = globalThis.WebSocket;
                globalThis.WebSocket = class extends NativeWebSocket {
                    send(payload) {
                        if (this.url.endsWith('/api/web-ui/socket') && (payload instanceof ArrayBuffer || ArrayBuffer.isView(payload))) {
                            const bytes = payload instanceof ArrayBuffer ? new Uint8Array(payload) : new Uint8Array(payload.buffer, payload.byteOffset, payload.byteLength);
                            const length = new DataView(bytes.buffer, bytes.byteOffset).getUint32(0);
                            const header = JSON.parse(new TextDecoder().decode(bytes.subarray(4, 4 + length)));
                            if (header.type === 'request' && header.path === '/api/web-ui/platform/devices/connect') {
                                const target = JSON.parse(new TextDecoder().decode(bytes.subarray(4 + length)));
                                if (target.host !== '127.0.0.2' || target.port !== allowed) throw new Error('设备连接超出隔离测试范围');
                            }
                        }
                        super.send(payload);
                    }
                };
            }, fixtures.runtimes[1].devicePort);
            const page = await context.newPage();
            page.on('console', message => {
                if (message.type() === 'warning' || message.type() === 'error') {
                    diagnostics.push({ index, message: message.text().slice(0, 1500) });
                }
            });
            page.on('websocket', socket => {
                socket.on('close', () => diagnostics.push({ index, socket: 'closed' }));
                socket.on('socketerror', error => diagnostics.push({ index, socketError: error }));
                for (const direction of ['framesent', 'framereceived']) socket.on(direction, frame => {
                    const bytes = Buffer.from(frame.payload);
                    if (bytes.length < 4) return;
                    const size = bytes.readUInt32BE(0);
                    const header = JSON.parse(bytes.subarray(4, 4 + size).toString());
                    if (header.type === 'auth') return;
                    if (direction === 'framesent' && header.type === 'request') management[index].requests++;
                    if (direction === 'framereceived' && header.type === 'event') {
                        management[index].events[header.eventType] = (management[index].events[header.eventType] || 0) + 1;
                    }
                    diagnostics.push({ index, direction, type: header.type, id: header.id,
                        path: header.path, status: header.status, event: header.eventType,
                        bytes: bytes.length - 4 - size });
                    if (diagnostics.length > 160) diagnostics.shift();
                });
            });
            page.platform = observePlatform(page);
            page.setDefaultTimeout(45000);
            // Never connect to another machine accidentally if a LAN device changes the drawer order.
            await page.route('**/platform/devices/connect', async route => {
                if (route.request().method() !== 'POST') { await route.continue(); return; }
                const target = route.request().postDataJSON();
                if (target.host !== '127.0.0.2' || target.port !== fixtures.runtimes[1].devicePort) {
                    errors.push('Attempted connection outside the disposable peer'); await route.abort();
                } else await route.continue();
            });
            page.on('pageerror', error => errors.push(error.stack || error.message));
            page.on('request', request => {
                const url = new URL(request.url());
                if (url.pathname.endsWith('/devices/transport') && ['data', 'body'].includes(url.searchParams.get('operation'))) {
                    deviceBodyRequests.push({ index, method: request.method(), operation: url.searchParams.get('operation') });
                }
            });
            if (index === 0) page.on('console', message => {
                if (message.text().includes('Device session is bound:')) deviceBound = true;
            });
            page.platform.on('request', request => {
                const pathname = new URL(request.url()).pathname;
                paths[index].push(pathname);
                if (pathname.endsWith('/http/requests')) outbound.push(request.postDataJSON().url);
                if (pathname.endsWith('/files/info')) fileRequests.push({ index, path: new URL(request.url()).searchParams.get('path') });
            });
            page.platform.on('response', response => {
                if (new URL(response.url()).pathname.endsWith('/files/info')) response.json().then(body => {
                    fileResponses.push({ index, status: response.status(), path: new URL(response.url()).searchParams.get('path'), directory: body.isDirectory, error: body.error });
                }).catch(() => {});
            });
            pages.push(page);
            await page.goto(fixtures.site.url);
            await page.getByRole('textbox').nth(1).click({ force: true });
            await render(page);
            await page.keyboard.insertText((await fs.readFile(path.join(runtime.directory, 'secret.txt'), 'utf8')).trim());
            await render(page);
            const [login] = await Promise.all([
                page.platform.waitForResponse(r => r.url() === runtime.url + '/api/web-ui/login' && r.request().method() === 'POST'),
                page.platform.waitForResponse(r => r.url().endsWith('/platform/listeners') && r.request().method() === 'POST' && r.status() === 200),
                page.getByRole('button', { name: 'Sign in', exact: true }).click({ force: true }),
            ]);
            assert.equal(login.status(), 200);
            sessions.push(await login.json());
            await render(page);
            console.log(`Peer ${index} ready`);
        }
        const [page, peer] = pages;
        // Compose renders its main surface to canvas. Fixed viewport coordinates follow the captured UI.
        await click(page, 268, 292);
        await fill(page, 500, 211, 'Peer share fixture');
        await fill(page, 813, 211, `http://127.0.0.1:${fixtures.runtimes[1].sharePort}/`);
        const shareResponse = page.platform.waitForResponse(r => r.url().includes('/http/download?') && r.request().method() === 'GET' && r.status() === 200);
        await click(page, 1172, 846); await shareResponse;
        await page.screenshot({ path: path.join(output, 'share-network.png') });
        // The test button stays busy while its success snackbar is visible.
        await page.waitForTimeout(4500);
        await click(page, 1048, 846);
        await until(async () => (await savedNetworks(page, fixtures.runtimes[0].url)).some(row => row[0] === 'Peer share fixture'), 'Share configuration was not persisted');
        assert.ok(outbound.some(url => new URL(url).port === String(fixtures.runtimes[1].sharePort)));
        checks.push('Link-sharing network uses the HTTP engine and peer browser callbacks; configuration persists');
        console.log(checks.at(-1));

        await click(page, 268, 292); await click(page, 1000, 95);
        await page.screenshot({ path: path.join(output, 'protocol-menu.png') });
        await click(page, 415, 343);
        await page.screenshot({ path: path.join(output, 'protocol-selected.png') });
        await fill(page, 500, 211, 'WebDAV fixture');
        await fill(page, 813, 211, `http://127.0.0.1:${dav.address().port}/`);
        const listing = page.platform.waitForResponse(r => r.url().includes('/networks/directories?') && r.request().method() === 'GET' && r.status() === 200);
        await click(page, 1172, 846);
        const batch = await (await listing).json();
        assert.equal(batch.entries[0].name, 'remote.txt'); assert.ok(davRequests > 0);
        await page.screenshot({ path: path.join(output, 'webdav-network.png') });
        await page.waitForTimeout(4500); await click(page, 1048, 846);
        await until(async () => (await savedNetworks(page, fixtures.runtimes[0].url)).some(row => row[0] === 'WebDAV fixture'), 'WebDAV configuration was not persisted');
        assert.deepEqual(await savedNetworks(peer, fixtures.runtimes[1].url), []);
        checks.push('Browser network actual → WebSocket platform → native WebDAV listing; saved networks isolated');
        console.log(checks.at(-1));

        await click(page, 268, 222);
        await page.screenshot({ path: path.join(output, 'device-connect-dialog.png') });
        await fill(page, 600, 438, '127.0.0.2:' + fixtures.runtimes[1].devicePort);
        const identified = page.platform.waitForResponse(r => r.url().endsWith('/devices/connect') && r.request().method() === 'POST' && r.status() === 200);
        await click(page, 744, 521); await identified; await page.waitForTimeout(1000);
        await click(page, 332, 222);
        await page.screenshot({ path: path.join(output, 'device-list.png') });
        await click(page, 145, 299); await click(page, 848, 496);
        await peer.bringToFront(); await peer.waitForTimeout(1000);
        await peer.screenshot({ path: path.join(output, 'device-approval.png') });
        await click(peer, 1219, 847); await click(peer, 586, 851);
        await peer.screenshot({ path: path.join(output, 'device-role.png') });
        await click(peer, 408, 432); await click(peer, 858, 552);
        await until(() => deviceBound, 'Device session was not approved');
        await Promise.all([
            peer.platform.waitForResponse(r => r.url().includes('/files/directories?') && r.request().method() === 'GET' && r.status() === 200),
            click(page, 145, 299),
        ]);
        await render(page); await click(page, 1253, 31);
        const peerHome = fixtures.runtimes[1].directory + '/home';
        let typedPath;
        for (let attempt = 0; attempt < 3 && !typedPath; attempt++) {
            // Compose may replace its hidden text input while the dialog gains focus.
            const autocomplete = peer.platform.waitForRequest(r => r.url().endsWith('/files/directories') &&
                r.method() === 'POST' && r.postDataJSON().path === peerHome, { timeout: 5000 }).catch(() => null);
            await fill(page, 558, 444, peerHome);
            typedPath = await autocomplete;
        }
        assert.ok(typedPath, 'Directory input did not reach the browser business handler');
        await page.screenshot({ path: path.join(output, 'device-directory.png') });
        // Typing also lists directories for autocomplete. Wait for confirmation's
        // metadata lookup before observing the current directory's refresh.
        const directoryChanged = peer.platform.waitForResponse(r => {
            const url = new URL(r.url());
            return url.pathname.endsWith('/files/info') && url.searchParams.get('path') === peerHome && r.request().method() === 'GET' && r.status() === 200;
        }).then(() => peer.platform.waitForResponse(r => r.url().endsWith('/files/directories') &&
            r.request().method() === 'POST' && r.request().postDataJSON().path === peerHome && r.status() === 200));
        await Promise.all([directoryChanged, click(page, 858, 514)]);
        await render(page);
        const name = `peer-${Date.now()}.bin`, length = 1024 * 1024 + 33;
        await page.evaluate(({ name, length }) => {
            const transfer = new DataTransfer();
            transfer.items.add(new File([new Uint8Array(length).fill(43)], name));
            document.dispatchEvent(new DragEvent('drop', { dataTransfer: transfer, bubbles: true, cancelable: true }));
        }, { name, length });
        const destination = path.join(fixtures.runtimes[1].directory, 'home', name);
        await until(async () => (await fs.stat(destination).catch(() => null))?.size === length, 'Device transfer did not finish', 45000);
        assert.deepEqual(await fs.readFile(destination), Buffer.alloc(length, 43));
        await page.screenshot({ path: path.join(output, 'device-transfer.png') });
        for (let index = 0; index < 2; index++) {
            assert.ok(paths[index].some(p => p.endsWith('/devices/transport')));
            assert.ok(management[index].requests > 0 && management[index].events['device-frames'] > 0);
            assert.ok(management[index].events['device-progress'] > 0);
        }
        assert.deepEqual(deviceBodyRequests, []);
        checks.push('Web approval and role selection establish TLS device session; shared task transfers more than 1 MiB');
        assert.deepEqual(errors, []);
        await fs.writeFile(path.join(output, 'result.json'), JSON.stringify({ passed: true, checks, management, deviceBodyRequests, bytes: length }, null, 2));
        console.log(JSON.stringify({ passed: true, checks }));
    } catch (error) {
        for (let i = 0; i < pages.length; i++) await pages[i].screenshot({ path: path.join(output, `failure-${i}.png`) }).catch(() => {});
        await fs.writeFile(path.join(output, 'failure.json'), JSON.stringify({ message: error.message, errors, checks, paths, fileRequests, fileResponses, diagnostics }, null, 2));
        throw error;
    } finally {
        // Explicitly release listeners so the fixture can be reused immediately.
        for (let i = 0; i < sessions.length; i++) await fetch(fixtures.runtimes[i].url + '/api/web-ui/logout', {
            method: 'POST', headers: { 'X-FolderSpan-WebUi-Session': sessions[i].sessionToken, 'X-FolderSpan-WebUi-Version': '4' },
            signal: AbortSignal.timeout(5000),
        }).catch(() => {});
        await browser?.close();
        dav.closeAllConnections(); await new Promise(resolve => dav.close(resolve));
    }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
