// Observe real HTTP and WebSocket platform operations without changing browser traffic.
const { EventEmitter } = require('node:events');
function decode(payload) {
    const bytes = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
    const length = bytes.readUInt32BE(0);
    return { header: JSON.parse(bytes.subarray(4, 4 + length).toString()), body: bytes.subarray(4 + length) };
}
function observePlatform(page) {
    const events = new EventEmitter();
    const sockets = [];
    page.on('request', request => events.emit('request', request));
    page.on('response', response => events.emit('response', response));
    page.on('websocket', socket => {
        if (!socket.url().endsWith('/api/web-ui/socket')) return;
        sockets.push(socket);
        const pending = new Map();
        socket.on('framesent', frame => {
            const { header, body } = decode(frame.payload);
            if (header.type !== 'request') return;
            const url = new URL(header.path, socket.url().replace(/^ws/, 'http')).href;
            const request = { url: () => url, method: () => header.method,
                postDataJSON: () => JSON.parse(body.toString()), postData: () => body.toString() };
            pending.set(header.id, request);
            events.emit('request', request);
        });
        socket.on('framereceived', frame => {
            const { header, body } = decode(frame.payload);
            if (header.type !== 'response') return;
            const request = pending.get(header.id);
            pending.delete(header.id);
            if (!request) return;
            events.emit('response', { url: request.url, request: () => request,
                status: () => header.status, json: async () => JSON.parse(body.toString()), body: async () => body });
        });
    });
    function wait(event, predicate, options = {}) {
        return new Promise((resolve, reject) => {
            const done = value => {
                try {
                    if (!predicate(value)) return;
                    clearTimeout(timer); events.off(event, done); resolve(value);
                } catch (error) { clearTimeout(timer); events.off(event, done); reject(error); }
            };
            const timer = setTimeout(() => { events.off(event, done); reject(new Error(`等待平台 ${event} 超时`)); }, options.timeout || 30000);
            events.on(event, done);
        });
    }
    return { sockets, on: (event, listener) => events.on(event, listener), waitForRequest: (predicate, options) => wait('request', predicate, options),
        waitForResponse: (predicate, options) => wait('response', predicate, options) };
}

// A separate disposable authenticated session is used only for auxiliary CRUD assertions.
// The application retains its own connection and request ids unchanged.
async function fixtureCommand({ origin, secret, method, suffix, body }) {
    const cache = globalThis.__webUiFixtureSessions ||= new Map();
    let session = cache.get(origin);
    function encode(header, body = new Uint8Array()) {
        const metadata = new TextEncoder().encode(JSON.stringify(header));
        const bytes = new Uint8Array(4 + metadata.length + body.length);
        new DataView(bytes.buffer).setUint32(0, metadata.length);
        bytes.set(metadata, 4); bytes.set(body, 4 + metadata.length);
        return bytes;
    }
    if (!session) {
        const login = await fetch(origin + '/api/web-ui/login', { method: 'POST', credentials: 'omit', redirect: 'error',
            headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ secret, protocolVersion: 4 }) });
        if (!login.ok) throw new Error('辅助会话登录失败');
        const token = (await login.json()).sessionToken;
        const socket = new WebSocket(origin.replace(/^http/, 'ws') + '/api/web-ui/socket');
        socket.binaryType = 'arraybuffer';
        session = { socket, nextId: 0, pending: new Map() };
        await new Promise((resolve, reject) => {
            const timeout = setTimeout(() => { socket.close(); reject(new Error('辅助会话认证超时')); }, 10000);
            socket.onopen = () => socket.send(encode({ type: 'auth', token, version: 4 }));
            socket.onerror = () => { clearTimeout(timeout); reject(new Error('辅助会话连接失败')); };
            socket.onclose = () => {
                clearTimeout(timeout); cache.delete(origin);
                reject(new Error('辅助会话关闭'));
                for (const pending of session.pending.values()) pending.reject(new Error('辅助会话关闭'));
                session.pending.clear();
            };
            socket.onmessage = event => {
                const bytes = new Uint8Array(event.data);
                const length = new DataView(bytes.buffer).getUint32(0);
                const header = JSON.parse(new TextDecoder().decode(bytes.subarray(4, 4 + length)));
                const payload = bytes.subarray(4 + length);
                if (header.type === 'authenticated') {
                    clearTimeout(timeout);
                    if (header.status === 200) resolve(); else reject(new Error('辅助会话认证失败'));
                } else if (header.type === 'heartbeat') socket.send(encode({ type: 'heartbeat' }));
                else if (header.type === 'response') {
                    const pending = session.pending.get(header.id); session.pending.delete(header.id);
                    if (pending) { clearTimeout(pending.timeout); pending.resolve({ status: header.status,
                        text: new TextDecoder().decode(payload), bytes: Array.from(payload) }); }
                }
            };
        });
        cache.set(origin, session);
    }
    return new Promise((resolve, reject) => {
        const id = ++session.nextId;
        const timeout = setTimeout(() => { session.pending.delete(id); reject(new Error('辅助命令超时')); }, 10000);
        session.pending.set(id, { resolve, reject, timeout });
        session.socket.send(encode({ type: 'request', id, method, path: '/api/web-ui/' + suffix,
            contentType: body === undefined ? '' : 'application/json' },
            body === undefined ? new Uint8Array() : new TextEncoder().encode(JSON.stringify(body))));
    });
}
module.exports = { observePlatform, fixtureCommand };
