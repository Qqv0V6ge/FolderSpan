config.resolve = config.resolve || {};
config.resolve.fallback = {
  ...(config.resolve.fallback || {}),
  crypto: false,
  os: false,
  path: false
};

config.module = config.module || {};
config.module.rules = config.module.rules || [];
config.module.rules.push({
  test: /sql-wasm\.wasm$/,
  type: "asset/resource",
  generator: {
    filename: "sql-wasm.wasm"
  }
});
