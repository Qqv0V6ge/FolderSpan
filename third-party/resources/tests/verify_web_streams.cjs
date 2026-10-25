'use strict';

// 仅用Node内置模块执行真实Streams实现；VM不是安全隔离边界。
// 来源：https://nodejs.org/api/vm.html
// 包接口：https://github.com/MattiasBuelens/web-streams-polyfill/tree/v2.0.2
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../../..');
const product = 'core/src/commonMain/composeResources/files/share-file/static/streamsaver/polyfill.min.js';
const target = path.resolve(root, process.argv[2] || product);
const reportPath = process.argv[3] && path.resolve(root, process.argv[3]);
const officialSha256 = '71037540957d86a5226328dc8842374839cc7292942eb5ed29a245450b63d0f3';
const bytes = fs.readFileSync(target);
const source = bytes.toString('utf8');
const sourceSha256 = crypto.createHash('sha256').update(bytes).digest('hex');
const requiredExports = ['ReadableStream', 'WritableStream', 'TransformStream',
  'ByteLengthQueuingStrategy', 'CountQueuingStrategy'];
const report = { node: process.version, target: path.relative(root, target), sourceSha256,
  officialSha256, tests: [], browserServiceWorkerTested: false };

function load(extra = {}) {
  const context = vm.createContext({ Uint8Array, ArrayBuffer,
    console: { warn() {}, log() {}, error() {} }, ...extra });
  context.window = context;
  context.self = context;
  new vm.Script(source, { filename: target }).runInContext(context, { timeout: 1000 });
  return context;
}

const tick = () => new Promise(resolve => setImmediate(resolve));
async function run(name, body) {
  let timer;
  try {
    await Promise.race([Promise.resolve().then(body), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('测试超过4秒')), 4000);
    })]);
    report.tests.push({ name, passed: true });
  } catch (error) {
    report.tests.push({ name, passed: false, error: String(error.stack || error) });
  } finally { clearTimeout(timer); }
}

function streams() { return load().WebStreamsPolyfill; }

// DOM与消息通道仅在浏览器边界用内存替身；Streams构造器和项目StreamSaver是真代码。
function streamSaverContext(nativeWritable) {
  const ports = [];
  class MemoryChannel {
    constructor() {
      const port = () => ({ messages: [], closed: false,
        postMessage(value) { this.messages.push(value); },
        close() { this.closed = true; } });
      this.port1 = port(); this.port2 = port(); ports.push(this.port1, this.port2);
    }
  }
  const document = { documentElement: { style: {} },
    createElement(tag) {
      assert.equal(tag, 'iframe');
      return { listeners: {}, contentWindow: { postMessage() {} },
        addEventListener(event, callback) { this.listeners[event] = callback; } };
    },
    body: { appendChild(element) { element.listeners.load(); } } };
  const context = load({ ReadableStream, Response, MessageChannel: MemoryChannel,
    HTMLElement: function HTMLElement() {}, navigator: { serviceWorker: {} },
    isSecureContext: true, origin: 'https://streams-test.invalid',
    location: { origin: 'https://streams-test.invalid' }, document,
    ...(nativeWritable ? { WritableStream: nativeWritable } : {}) });
  const file = path.join(root, 'core/src/commonMain/composeResources/files/share-file/static/streamsaver/StreamSaver.js');
  new vm.Script(fs.readFileSync(file, 'utf8'), { filename: file })
    .runInContext(context, { timeout: 1000 });
  return { context, ports };
}

(async () => {
  await run('来源锁定：产品必须逐字等于官方2.0.2 dist/ponyfill.js', () => {
    assert.equal(sourceSha256, officialSha256, '当前文件不是已锁定的官方原字节');
  });
  await run('浏览器UMD导出项目所需五个构造器', () => {
    const exported = streams();
    report.exportedNames = Object.keys(exported).sort();
    for (const name of requiredExports) assert.equal(typeof exported[name], 'function');
  });
  await run('ponyfill不覆盖宿主原生Readable/Writable/Transform', () => {
    const original = { ReadableStream() {}, WritableStream() {}, TransformStream() {} };
    const context = load(original);
    for (const name of Object.keys(original)) assert.equal(context[name], original[name]);
  });
  await run('Readable读取和pipeTo保持字节顺序并关闭sink', async () => {
    const P = streams(); const received = []; let closed = false;
    const source = new P.ReadableStream({ start(controller) {
      controller.enqueue(new Uint8Array([1, 2]));
      controller.enqueue(new Uint8Array([3])); controller.close();
    } });
    await source.pipeTo(new P.WritableStream({
      write(chunk) { received.push(...chunk); }, close() { closed = true; }
    }));
    assert.deepEqual(received, [1, 2, 3]); assert.equal(closed, true);
  });
  await run('Writable背压等待首写入完成，ready与close正确结算', async () => {
    const P = streams(); const events = []; let release;
    const sink = new P.WritableStream({ write(value) {
      events.push(value);
      if (value === 1) return new Promise(resolve => { release = resolve; });
    }, close() { events.push('close'); } }, { highWaterMark: 1, size: () => 1 });
    const writer = sink.getWriter(); const first = writer.write(1); const second = writer.write(2);
    let ready = false; const readiness = writer.ready.then(() => { ready = true; });
    await tick(); assert.deepEqual(events, [1]); assert.equal(ready, false);
    assert.ok(writer.desiredSize <= 0); release(); await Promise.all([first, second, readiness]);
    await writer.close(); assert.deepEqual(events, [1, 2, 'close']); assert.equal(ready, true);
  });
  await run('Readable reader.cancel向真实source传递取消原因', async () => {
    const P = streams(); let canceled;
    const readable = new P.ReadableStream({ start(controller) { controller.enqueue(7); },
      cancel(reason) { canceled = reason; } });
    const reader = readable.getReader(); assert.equal((await reader.read()).value, 7);
    await reader.cancel('用户取消'); assert.equal(canceled, '用户取消');
    assert.equal((await reader.read()).done, true);
  });
  await run('Writable writer.abort传递原因并拒绝后续write', async () => {
    const P = streams(); let aborted;
    const writer = new P.WritableStream({ abort(reason) { aborted = reason; } }).getWriter();
    writer.closed.catch(() => {}); await writer.abort('终止写入');
    assert.equal(aborted, '终止写入'); await assert.rejects(writer.write(1));
  });
  await run('pipeTo的sink失败会取消source且保留错误', async () => {
    const P = streams(); const failure = new Error('sink失败'); let canceled;
    const readable = new P.ReadableStream({ start(controller) { controller.enqueue(1); },
      cancel(reason) { canceled = reason; } });
    const writable = new P.WritableStream({ write() { throw failure; } });
    await assert.rejects(readable.pipeTo(writable), error => error === failure);
    assert.equal(canceled, failure);
  });
  await run('TransformStream经pipeThrough转换并flush收尾', async () => {
    const P = streams(); const received = [];
    const transform = new P.TransformStream({ transform(chunk, controller) {
      controller.enqueue(new Uint8Array(Array.from(chunk, value => value * 2)));
    }, flush(controller) { controller.enqueue(new Uint8Array([9])); } });
    const readable = new P.ReadableStream({ start(controller) {
      controller.enqueue(new Uint8Array([1, 2])); controller.close();
    } });
    await readable.pipeThrough(transform).pipeTo(new P.WritableStream({
      write(chunk) { received.push(...chunk); }
    }));
    assert.deepEqual(received, [2, 4, 9]);
    assert.equal(new P.ByteLengthQueuingStrategy({ highWaterMark: 8 }).size(new Uint8Array(3)), 3);
    assert.equal(new P.CountQueuingStrategy({ highWaterMark: 2 }).size('字节块'), 1);
  });
  await run('真实StreamSaver在缺少原生Writable时使用ponyfill写入和关闭', async () => {
    const { context, ports } = streamSaverContext();
    assert.equal(context.streamSaver.WritableStream, context.WebStreamsPolyfill.WritableStream);
    const writer = context.streamSaver.createWriteStream('fixture.bin', { size: 3 }).getWriter();
    await writer.write(new Uint8Array([1, 2])); await writer.write(new Uint8Array([3]));
    await writer.close();
    assert.deepEqual(ports[0].messages.map(value => typeof value === 'string' ? value : Array.from(value)),
      [[1, 2], [3], 'end']);
  });
  await run('真实StreamSaver的abort经ponyfill关闭消息通道', async () => {
    const { context, ports } = streamSaverContext();
    const writer = context.streamSaver.createWriteStream('canceled.bin', {}).getWriter();
    writer.closed.catch(() => {}); await writer.abort('用户取消');
    assert.deepEqual(ports[0].messages, ['abort']);
    assert.equal(ports[0].closed, true); assert.equal(ports[1].closed, true);
  });
  await run('真实StreamSaver优先保留已有的原生Writable', () => {
    function NativeWritableSentinel() {}
    const { context } = streamSaverContext(NativeWritableSentinel);
    assert.equal(context.streamSaver.WritableStream, NativeWritableSentinel);
  });
  report.passed = report.tests.filter(test => test.passed).length;
  report.failed = report.tests.length - report.passed;
  if (reportPath) fs.writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n');
  process.stdout.write(JSON.stringify(report, null, 2) + '\n');
  process.exitCode = report.failed ? 1 : 0;
})().catch(error => { console.error(error); process.exitCode = 1; });
