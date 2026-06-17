if (config.devServer) {
    config.devServer.hot = true;
    config.devServer.historyApiFallback = true;
    config.devServer.client = {
        overlay: false
    };
} else {
    config.devtool = undefined;
}

config.resolve = {
    ...config.resolve,
    fallback: {
        "fs": false,
        "path": false,
        // The `dropbox` SDK statically references these Node core modules in code paths only used
        // under Node (the browser uses global fetch / Web Crypto). Stub them so webpack doesn't fail
        // to resolve them in the browser bundle.
        "crypto": false,
        "util": false
    }
}
