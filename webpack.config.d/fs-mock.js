const path = require('path');

config.resolve = config.resolve || {};
config.resolve.alias = config.resolve.alias || {};
config.resolve.alias.fs = 'memfs';

config.resolve.fallback = config.resolve.fallback || {};
config.resolve.fallback.path = require.resolve('path-browserify');
config.resolve.fallback.os = require.resolve('os-browserify/browser');
config.resolve.fallback.buffer = require.resolve('buffer/');
config.resolve.fallback.stream = require.resolve('stream-browserify');
config.resolve.fallback.util = require.resolve('util/');
config.resolve.fallback.url = require.resolve('url/');
config.resolve.fallback.process = require.resolve('process/browser');
config.resolve.fallback.assert = require.resolve('assert/');

config.resolve.modules = config.resolve.modules || [];
config.resolve.modules.push(path.resolve(__dirname, 'node_modules'));

// Inject global variables that some modules expect
const webpack = require('webpack');
config.plugins = config.plugins || [];
config.plugins.push(
  new webpack.ProvidePlugin({
    process: 'process/browser.js',
    Buffer: ['buffer', 'Buffer']
  })
);

config.module = config.module || {};
config.module.rules = config.module.rules || [];
config.module.rules.push({
    test: /\.m?js/,
    resolve: {
        fullySpecified: false
    }
});
