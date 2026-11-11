const fs = require("fs");
const path = require("path");
const symbols = path.resolve(config.basePath, "kotlin/js-reexport-symbols.mjs");

// JS 测试没有应用入口，需先加载 Skiko 导出并等待其初始化。
if (path.basename(path.resolve(config.basePath, "../..")) === "js" && fs.existsSync(symbols)) {
    const setup = path.resolve(config.basePath, "../../../../app/shared/test-support/skiko-setup.mjs");
    config.files.unshift({ pattern: setup, included: true, served: true, watched: false });
    config.preprocessors[setup] = ["webpack"];
    config.webpack.resolve.alias = {
        ...config.webpack.resolve.alias,
        "folderspan-skiko-test-symbols": symbols
    };
}
