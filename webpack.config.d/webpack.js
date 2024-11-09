if (config.devServer) {
    config.devServer.hot = true;
    config.devServer.historyApiFallback = true;
    config.devServer.client = {
        overlay: false
    };
    // config.devtool = 'eval-cheap-source-map';
} else {
    config.devtool = undefined;
}

config.resolve = {
    ...config.resolve,
    fallback: {
        "fs": false,
        "path": false
    }
}
