# The Do Zone

Welcome to The Do Zone, a private, in-browser, offline-first, modal, sync-enabled, open source task and information manager (/"3d kanban board").

If you don't want to read details and prefer to just get into it, click to launch the app now: [The Do Zone](https://the.do.zone). There is a helpful getting started wizard which will walk you through the basics.

## Why another of these?

This app started as an experiment several years ago: I wanted to see how far you could get with a useful in-browser application without requiring a server. What if the data lived on the user's machine, and all work was done there? I was also curious to kick tires on kotlin.js, and see how usable it was.

Once it existed, I came to rely on it, and have slowly polished features as I needed them.

Recently, I decided a full rewrite was necessary; it's still using kotlin.js, but it's now using a framework that I wrote that provides a lot of niceties rather than the original framework it was based on (see: [Zoned](https://github.com/reubenfirmin/zoned) - core idea is that everything in a webapp, sql to css, routes included, can be typed.)

## Working in the codebase

I welcome pull requests, bug reports, or feature ideas. Main.kt is the entrypoint, and the code should be discoverable from there.

## Building locally

Prerequisites: JDK 21, plus Chrome/Chromium for the headless tests.

The app builds on the [Zoned](https://github.com/reubenfirmin/zoned) framework, consumed as a local `1.0-SNAPSHOT`. Publish it to your Maven local first (see Zoned's README), then:

    ./gradlew build   # compile and run the test suite

That build also generates two helper scripts (they're generated rather than checked in, so you won't see them until the first build):

    ./watch.sh        # recompile on change, serve at http://localhost:3000
    ./test.sh         # run the test suite; add --tests "*FooTest*" to filter

They use separate Kotlin compile daemons, so you can leave the watcher running while you run tests. Calling `./gradlew jsBrowserTest` by hand while the watcher is up makes the two compete for memory, so prefer `./test.sh`.

CI builds Zoned from `reubenfirmin/zoned@master`. If a change here depends on a change there, push that one first or CI won't reproduce your local build.

## Deploying

The site is a Cloudflare Worker serving static assets, configured in `wrangler.jsonc`, with two environments:

    ./gradlew build-production            # tests, plus the optimized hashed bundle
    npx wrangler deploy --env beta        # beta.do.zone
    npx wrangler deploy --env production  # the.do.zone

Two things worth knowing:

* Wrangler only uploads `build/dist/js/productionExecutable`. It never builds anything, so run the Gradle build first or you'll quietly ship whatever bundle was there before.
* Deploying without an `--env` targets a worker that has no routes attached, so neither site changes. Always pass one.

Pushing to `main` runs the same build in CI ([ci.yml](.github/workflows/ci.yml)) and deploys beta automatically, so production is usually the only one deployed by hand. CI authenticates via the `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` repository secrets; locally, `npx wrangler login` is enough.

The app is a PWA, so a client that's already open may keep serving its cached bundle after a deploy until it's reloaded.

## License

Released under the [MIT License](LICENSE).
