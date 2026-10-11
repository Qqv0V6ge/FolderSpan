#!/usr/bin/env node
// Disposable fixtures only: node scripts/verify_webui_browser.cjs fixtures.json output-dir
// See start_webui_fixtures.py. No dependency installation or production credentials are required.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.FOLDERSPAN_PLAYWRIGHT || 'playwright');
const { observePlatform, fixtureCommand } = require('./webui_transport.cjs');

async function main() {
    const fixtures = JSON.parse(await fs.readFile(process.argv[2], 'utf8'));
    const output = path.resolve(process.argv[3] || 'build/platform-http/browser');
    await fs.mkdir(output, { recursive: true });
    const secrets = await Promise.all(fixtures.runtimes.map(r => fs.readFile(path.join(r.directory, 'secret.txt'), 'utf8')));
    const browser = await chromium.launch({ executablePath: process.env.FOLDERSPAN_CHROMIUM, args: ['--enable-unsafe-swiftshader'] });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'en-US' });
    await context.addInitScript(runtimes => {
        const NativeWebSocket = globalThis.WebSocket;
        const managementSockets = [];
        globalThis.WebSocket = class extends NativeWebSocket {
            constructor(...args) {
                super(...args);
                if (this.url.endsWith('/api/web-ui/socket')) managementSockets.push(this);
            }
        };
        globalThis.__webUiCloseFixtureSockets = () => managementSockets.forEach(socket => socket.close());
        if (!/^https?:$/.test(location.protocol)) return;
        for (const runtime of runtimes) {
            const prefix = `runtime:${runtime.url.length}:${runtime.url}:`;
            const defaults = {
                'settings.app.onboarding.completed': 'true',
                'settings.app.welcomeAgreement.accepted': 'true',
                'settings.appearance.language': 'en',
                'settings.fileShare.port': String(runtime.devicePort),
                'settings.easyFileShare.port': String(runtime.sharePort),
                'settings.easyFileShare.autoStart': 'true',
                'settings.easyFileShare.sharePaths': JSON.stringify([runtime.directory + '/home/alpha.txt']),
                'settings.mcp.port': String(runtime.mcpPort),
            };
            for (const [key, value] of Object.entries(defaults)) {
                if (localStorage.getItem(prefix + key) === null) localStorage.setItem(prefix + key, value);
            }
        }
    }, fixtures.runtimes);
    const page = await context.newPage();
    const platform = observePlatform(page);
    const navigation = [];
    page.on('framenavigated', frame => { if (frame === page.mainFrame()) navigation.push(frame.url()); });
    const errors = [], requests = [], sessions = new Map(), checks = [], tokenOrigins = new Map();
    page.on('pageerror', error => errors.push(error.stack || error.message));
    page.on('response', async response => {
        if (response.url().endsWith('/api/web-ui/login') && response.status() === 200) {
            const login = await response.json(); tokenOrigins.set(login.sessionToken, new URL(response.url()).origin);
        }
    });
    page.on('request', request => {
        if (!request.url().includes('/api/web-ui/')) return;
        const headers = request.headers();
        requests.push({ method: request.method(), path: new URL(request.url()).pathname, origin: new URL(request.url()).origin,
            cookie: !!headers.cookie, token: headers['x-folderspan-webui-session'] });
    });
    const render = () => page.evaluate(() => new Promise(resolve => {
        let frames = 8;
        const tick = () => --frames ? requestAnimationFrame(tick) : resolve();
        requestAnimationFrame(tick);
    }));
    const click = async (x, y) => { await page.bringToFront(); await page.mouse.click(x, y); await render(); };
    const fill = async (x, y, value) => { await click(x, y); await page.keyboard.press('Control+A'); await page.keyboard.insertText(value); await render(); };
    async function field(index, value) {
        await page.getByRole('textbox').nth(index).click({ force: true });
        await render(); await page.keyboard.press('Control+A'); await page.keyboard.insertText(value); await render();
    }
    async function select(index, connectedCanvas = false) {
        if (connectedCanvas) {
            // The connected overlay uses Compose's canvas at the fixed test viewport.
            await page.screenshot({ path: path.join(output, 'switch-before.png') });
            let focused = false;
            for (let attempt = 0; attempt < 3 && !focused; attempt++) {
                await click(600, 330);
                focused = await page.waitForFunction(() => {
                    let element = document.activeElement;
                    while (element?.shadowRoot?.activeElement) element = element.shadowRoot.activeElement;
                    return element && (element.tagName === 'INPUT' || element.tagName === 'TEXTAREA' || element.isContentEditable);
                }, null, { timeout: 1500 }).then(() => true, () => false);
            }
            assert.ok(focused, 'Canvas runtime address field did not acquire input focus');
            await page.keyboard.press('Control+A');
            await page.keyboard.insertText(fixtures.runtimes[index].url); await render();
            await page.screenshot({ path: path.join(output, 'switch-address.png') });
            await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.mouse.click(640, 576)]);
        } else {
            await page.getByRole('heading', { name: 'Sign in to FolderSpan', exact: true }).waitFor();
            await field(0, fixtures.runtimes[index].url);
            await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.getByRole('button', { name: 'Switch target', exact: true }).click({ force: true })]);
        }
        await render();
        assert.equal(await page.evaluate(() => sessionStorage.getItem('folderspan.webui.target')), fixtures.runtimes[index].url);
    }
    async function login(index) {
        await field(1, secrets[index].trim());
        const businessStarted = platform.waitForRequest(r => r.url().includes('/platform/files/'), { timeout: 30000 });
        const authenticated = platform.waitForResponse(r => r.url() === fixtures.runtimes[index].url + '/api/web-ui/login' && r.request().method() === 'POST');
        await page.getByRole('button', { name: 'Sign in', exact: true }).click({ force: true });
        const response = await authenticated;
        assert.equal(response.status(), 200);
        sessions.set(index, await response.json());
        tokenOrigins.set(sessions.get(index).sessionToken, fixtures.runtimes[index].url);
        await businessStarted;
        await render();
        checks.push(`Runtime ${index} login starts browser business`);
        console.log(checks.at(-1));
    }
    async function api(index, method, suffix, body) {
        const selected = await page.evaluate(() => sessionStorage.getItem('folderspan.webui.target'));
        if (selected === fixtures.runtimes[index].url && !suffix.startsWith('platform/files/content?')) {
            return page.evaluate(fixtureCommand, { origin: fixtures.runtimes[index].url,
                secret: secrets[index].trim(), method, suffix, body });
        }
        return page.evaluate(async ({ origin, token, method, suffix, body }) => {
            const reply = await fetch(origin + '/api/web-ui/' + suffix, {
                method, credentials: 'omit', headers: { 'X-FolderSpan-WebUi-Session': token, 'X-FolderSpan-WebUi-Version': '4',
                    ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
                body: body === undefined ? undefined : JSON.stringify(body),
            });
            const bytes = new Uint8Array(await reply.arrayBuffer());
            return { status: reply.status, text: new TextDecoder().decode(bytes), bytes: Array.from(bytes) };
        }, { origin: fixtures.runtimes[index].url, token: sessions.get(index).sessionToken, method, suffix, body });
    }
    async function fileFlow(index) {
        const root = path.join(fixtures.runtimes[index].directory, 'home');
        const name = `浏览器 é +#-${Date.now()}.bin`;
        const bytes = Array.from({ length: 1024 * 1024 + 73 }, (_, i) => i % 251);
        await page.screenshot({ path: path.join(output, `before-files-${index}.png`) });
        await page.evaluate(({ name, bytes }) => {
            const transfer = new DataTransfer();
            transfer.items.add(new File([new Uint8Array(bytes)], name, { type: 'application/octet-stream' }));
            document.dispatchEvent(new DragEvent('drop', { dataTransfer: transfer, bubbles: true, cancelable: true }));
        }, { name, bytes });
        const destination = path.join(root, name);
        const deadline = Date.now() + 30000;
        while ((await fs.stat(destination).catch(() => null))?.size !== bytes.length) {
            assert.ok(Date.now() < deadline, 'Shared browser import task did not finish');
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        assert.deepEqual(await fs.readFile(destination), Buffer.from(bytes));
        const range = await api(index, 'GET', 'platform/files/content?path=' + encodeURIComponent(destination) + '&offset=1048570&length=10');
        assert.equal(range.status, 200);
        assert.deepEqual(range.bytes, bytes.slice(1048570, 1048580));
        assert.equal((await api(index, 'POST', 'platform/files/rename', { path: root, oldName: name, newName: name + '.renamed' })).status, 200);
        assert.equal((await api(index, 'DELETE', 'platform/files?path=' + encodeURIComponent(destination + '.renamed'))).status, 200);
        assert.equal(await fs.stat(destination + '.renamed').catch(() => null), null);
        await page.screenshot({ path: path.join(output, `files-${index}.png`) });
        checks.push(`Runtime ${index} shared import task and binary chunks; WebSocket rename/delete`);
        console.log(checks.at(-1));
    }
    try {
        await page.goto(fixtures.site.url, { waitUntil: 'load' });
        await select(0);
        await login(0);
        await fileFlow(0);
        // A real external request must be answered by the shared handler in this browser.
        const sharing = await fetch(`http://127.0.0.1:${fixtures.runtimes[0].sharePort}/`, { signal: AbortSignal.timeout(15000) });
        assert.equal(sharing.status, 200);
        assert.equal(await sharing.text(), 'a: alpha');
        const rangeShare = await fetch(`http://127.0.0.1:${fixtures.runtimes[0].sharePort}/alpha.txt`, {
            headers: { Range: 'bytes=2-5' }, signal: AbortSignal.timeout(15000),
        });
        assert.equal(rangeShare.status, 206);
        assert.equal(await rangeShare.text(), ' alp');
        checks.push('Share listener → WebSocket callback → browser business → streamed HTTP response');
        const firstCache = sessions.get(0).host.cache;
        await click(332, 31);
        await click(450, 232);
        await select(1, true);
        await login(1);
        await fileFlow(1);
        const storage = await page.evaluate(() => Object.fromEntries(Object.entries(localStorage)));
        for (let index = 0; index < 2; index++) {
            assert.ok(Object.keys(storage).some(key => key.startsWith(`runtime:${fixtures.runtimes[index].url.length}:${fixtures.runtimes[index].url}:`)));
        }
        assert.ok(!JSON.stringify(storage).includes(secrets[0].trim()) && !JSON.stringify(storage).includes(secrets[1].trim()));
        assert.equal((await api(0, 'GET', 'platform/files/roots')).status, 401);
        checks.push('Runtime switch isolates storage and invalidates old session');
        await context.setOffline(true);
        // Chromium's offline emulation preserves established WebSockets; terminate those fixture connections explicitly.
        await page.evaluate(() => globalThis.__webUiCloseFixtureSockets());
        // Session invalidation reloads the document to stop all browser business jobs.
        // With the entire browser offline, Chromium correctly shows its network error page.
        await page.waitForFunction(() => document.body?.textContent.includes('ERR_INTERNET_DISCONNECTED'), null, { timeout: 40000 });
        await context.setOffline(false);
        await page.reload({ waitUntil: 'domcontentloaded' });
        await login(1);
        const secondCache = sessions.get(1).host.cache;
        await page.close();
        const deadline = Date.now() + 70000;
        while (await fs.stat(secondCache).catch(() => null)) {
            assert.ok(Date.now() < deadline, 'Page-close lease did not release the session cache');
            await new Promise(resolve => setTimeout(resolve, 500));
        }
        assert.equal(await fs.stat(firstCache).catch(() => null), null);
        checks.push('Management socket interruption, offline login recovery and page-close resource lease');
        for (const request of requests) {
            assert.equal(request.cookie, false);
            if (request.token) {
                assert.equal(tokenOrigins.get(request.token), request.origin, 'Session credential sent to the wrong Runtime');
            }
        }
        assert.ok(platform.sockets.length >= 3, '登录后应使用管理 WebSocket');
        assert.ok(!requests.some(request => request.path === '/api/web-ui/events'), '管理长轮询应已移除');
        assert.deepEqual(errors, []);
        await fs.writeFile(path.join(output, 'result.json'), JSON.stringify({ passed: true, checks, requestCount: requests.length }, null, 2));
        console.log(JSON.stringify({ passed: true, checks }));
    } catch (error) {
        if (!page.isClosed()) {
            await page.screenshot({ path: path.join(output, 'failure.png') }).catch(() => {});
            await fs.writeFile(path.join(output, 'failure-dom.txt'), await page.locator('body').innerText().catch(() => ''));
        }
        await fs.writeFile(path.join(output, 'failure.json'), JSON.stringify({ message: error.message, errors, checks, navigation, selectedTarget: await page.evaluate(() => sessionStorage.getItem('folderspan.webui.target')).catch(() => null), requests: requests.map(({token, ...request}) => request) }, null, 2));
        throw error;
    } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
