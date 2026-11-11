const sqlPath = require("path");
config.client.mocha = { ...config.client.mocha, timeout: 360000 };
config.browserNoActivityTimeout = 360000;
for (const name of ["sqljs.worker.js", "sql-wasm.js", "sql-wasm.wasm"]) {
    config.files.push({
        pattern: sqlPath.resolve(config.basePath, "kotlin", name),
        included: false,
        served: true,
        watched: false,
    });
}
