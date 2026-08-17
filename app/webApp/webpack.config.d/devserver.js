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

    // Serve index.html for the OAuth callback deep link, so /oauth/callback?code=... boots the SPA
    // instead of 404ing. `historyApiFallback` defaults to false and Kotlin's DevServer DSL does not
    // expose it, which is why it is set here rather than in build.gradle.kts.
    //
    // Middleware order is host-header-check -> cross-origin-header-check -> proxy -> dev-middleware
    // -> static -> connect-history-api-fallback, so /mal is claimed by the proxy above long before
    // the fallback sees it, and the fallback only catches what nothing else served.
    //
    // Two behaviours of connect-history-api-fallback that look like bugs otherwise:
    //   - It rewrites `req.url` to `options.index`, DISCARDING the query string. That is server-side
    //     only: the address bar still carries ?code=..., so window.location.search is intact and
    //     `currentSearch()` in :app:shared/webMain reads it fine.
    //   - It only rewrites GET/HEAD with an HTML-ish Accept, and skips any path whose last segment
    //     contains a dot (`disableDotRule: false` keeps that rule on, which is what stops a missing
    //     webApp.js from being answered with index.html). So the callback path must stay
    //     extension-less: /oauth/callback, never /callback.html.
    config.devServer.historyApiFallback = {
        index: '/index.html',
        disableDotRule: false,
    };
}
