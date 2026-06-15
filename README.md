# The Do Zone

Welcome to The Do Zone, a private, in-browser, offline-first, modal, sync-enabled, open source task and information manager (/"3d kanban board").

If you don't want to read details and prefer to just get into it, click to launch the app now: [The Do Zone](https://the.do.zone). There is a helpful getting started wizard which will walk you through the basics.

## Why another of these?

This app started as an experiment several years ago: I wanted to see how far you could get with a useful in-browser application without requiring a server. What if the data lived on the user's machine, and all work was done there? I was also curious to kick tires on kotlin.js, and see how usable it was.

Once it existed, I came to rely on it, and have slowly polished features as I needed them.

Recently, I decided a full rewrite was necessary; it's still using kotlin.js, but it's now using a framework that I wrote that provides a lot of niceties rather than the original framework it was based on (see: [Zoned](https://github.com/reubenfirmin/zoned) - core idea is that everything in a webapp, sql to css, routes included, can be typed.)

## Working in the codebase

I welcome pull requests, bug reports, or feature ideas. Main.kt is the entrypoint, and the code should be discoverable from there.
