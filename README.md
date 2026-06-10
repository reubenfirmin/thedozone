## Building

To run in watch mode for development:

./gradlew watch

To build for production deploy:

./gradlew clean jsBrowserProductionWebpack

## CI / Deploy

CI is GitHub Actions (`.github/workflows/ci.yml`):

- **build** (every push + PR): checks out `reubenfirmin/zoned`, publishes its
  `1.0-SNAPSHOT` artifacts to Maven Local, then builds + tests this repo and
  uploads the assembled site (`build/dist/js/productionExecutable`).
- **deploy** (push to `main` only): runs `wrangler deploy`, shipping the static
  site to a Cloudflare Worker bound to **beta.do.zone**.

### One-time manual setup (repo owner)

1. **`do.zone` must be an active zone** in the target Cloudflare account (so the
   `beta.do.zone` custom-domain DNS record can be created).
2. **Create a Cloudflare API token** with:
   - Account → *Workers Scripts*: Edit
   - Zone (`do.zone`) → *Workers Routes*: Edit
   - Zone (`do.zone`) → *DNS*: Edit
   - Account → *Account Settings*: Read
3. **Add repository secrets** (Settings → Secrets and variables → Actions):
   - `CLOUDFLARE_API_TOKEN`
   - `CLOUDFLARE_ACCOUNT_ID`
4. The **first deploy** should be triggered/verified by the owner (via a push to
   `main` or the workflow's "Run workflow" button).

### Local deploy (optional)

    ./gradlew clean jsBrowserDistribution
    CLOUDFLARE_API_TOKEN=... CLOUDFLARE_ACCOUNT_ID=... npx wrangler deploy

