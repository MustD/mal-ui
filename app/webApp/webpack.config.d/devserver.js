// Dev server settings shared by the js and wasmJs browser targets.
//
// Ports are deliberately NOT set here — they differ per target and come from
// build.gradle.kts (wasmJs 18020, js 18030). This file only holds what is identical
// for both. Guarded because webpack.config.d is also applied to production bundling,
// where there is no devServer.
if (config.devServer) {
    // Bind IPv4 explicitly. The reverse proxy forwards to 127.0.0.1, while `localhost`
    // resolves to both 127.0.0.1 and ::1 here — binding the wrong one gives a connection
    // refused through the proxy with the dev server apparently running fine.
    config.devServer.host = '127.0.0.1';

    // A leading dot allows the host and all its subdomains. Without this the dev server
    // answers `Invalid Host header` to anything but localhost, so requests arriving via
    // https://mal-ui.localhost never reach the app.
    config.devServer.allowedHosts = ['.localhost'];

    // Keep the MAL relay same-origin when the app is opened on the dev-server port
    // directly, mirroring what Caddy does for mal-ui.localhost. MyAnimeList sends no
    // CORS headers, so anything cross-origin is blocked by the browser before it is sent.
    // Harmless behind the proxy: Caddy claims /mal before it reaches the dev server.
    config.devServer.proxy = [
        {
            context: ['/mal'],
            target: 'http://127.0.0.1:18010',
        },
    ];
}
