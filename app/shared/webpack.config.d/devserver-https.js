if (config.devServer) {
  if (typeof config.devServer.server === "object" && config.devServer.server !== null) {
    config.devServer.server = {
      ...config.devServer.server,
      type: "https"
    };
  } else {
    config.devServer.server = "https";
  }
}
