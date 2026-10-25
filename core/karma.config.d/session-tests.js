// Real ICE/SCTP establishment can exceed Mocha's two-second unit-test default.
if (!process.env.CHROME_BIN) {
    const fs = require('fs');
    const path = require('path');
    const cache = path.join(require('os').homedir(), '.cache', 'ms-playwright');
    const browser = fs.existsSync(cache) && fs.readdirSync(cache).filter(name => name.startsWith('chromium-')).sort().reverse()
        .map(name => path.join(cache, name, 'chrome-linux', 'chrome')).find(file => fs.existsSync(file));
    if (browser) process.env.CHROME_BIN = browser;
}
config.client = config.client || {};
if (process.env.FOLDERSPAN_WEBRTC_INTEROP_PORT) {
    config.client.webrtcInteropPort = process.env.FOLDERSPAN_WEBRTC_INTEROP_PORT;
}
if (process.env.FOLDERSPAN_WEBRTC_INTEROP_URL) {
    config.client.webrtcInteropUrl = process.env.FOLDERSPAN_WEBRTC_INTEROP_URL;
    config.client.webrtcInteropConfig = process.env.FOLDERSPAN_WEBRTC_INTEROP_CONFIG || '{}';
}
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 60000 });
config.failOnEmptyTestSuite = true;
config.browserNoActivityTimeout = 65000;
// Keep loopback ICE independent of the host's multicast DNS configuration.
config.customLaunchers = Object.assign({}, config.customLaunchers, {
    ChromeHeadlessWebRtc: {
        base: 'ChromeHeadless',
        flags: [
            '--disable-features=WebRtcHideLocalIpsWithMdns',
            '--allow-loopback-in-peer-connection',
            '--enable-precise-memory-info',
            '--use-fake-ui-for-media-stream',
            '--use-fake-device-for-media-stream',
            '--force-webrtc-ip-handling-policy=default',
        ],
    },
});
config.browsers = ['ChromeHeadlessWebRtc'];
config.files.unshift(require('path').resolve(
    config.basePath, '../../../../core/src/webTest/resources/webrtc-test-setup.js',
));
