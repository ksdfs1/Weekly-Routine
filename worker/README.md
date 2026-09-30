# Weekly Routine API (Cloudflare Worker)

Tiny storage backend so the GitHub Pages front-end (`../index.html`) can sync
the routine across devices. One JSON blob, one KV key, one write token.

## Deploy (run once, after `wrangler login`)

```bash
cd worker
npx wrangler kv namespace create ROUTINE_KV
# copy the printed "id" into wrangler.toml (kv_namespaces[0].id)

npx wrangler secret put WRITE_TOKEN
# paste the token when prompted — this is the "editing password" for the app

npx wrangler deploy
# prints the worker URL, e.g. https://weekly-routine-api.<your-subdomain>.workers.dev
```

Then put that URL into `API_BASE` at the top of `../index.html`'s script,
commit, and push — GitHub Pages picks it up automatically.

## Redeploying after edits

```bash
cd worker
npx wrangler deploy
```

No need to recreate the KV namespace or re-set the secret — those persist.
