# Astral Glow Registry

Small reference service for AstralGlow's opt-in presence sharing.

It does three things:
- accepts `POST /share` requests containing only `uuid` and `username`
- serves the public registry at `GET /glow_list.json`
- can optionally mirror that public registry back into a GitHub repo such as `jackforg/Astral-Addon`

## Why this exists

Astral's `share-presence` client setting is intentionally opt-in and transparent. The addon can send a user's UUID and username, but something on the server side still has to:
- store those opt-ins
- turn them into a public JSON list
- optionally keep a GitHub-hosted public copy in sync for easy inspection

This is the missing piece between Astral clients sharing presence and other clients reading a public glow list.

## Data stored

The public registry contains:
- `uuid`
- `username`
- `source`
- `optedInAt`
- `lastSeenAt`

The service does not store chat, coordinates, inventory, server IPs, tokens, or account credentials.

## Run locally

```bash
cd tools/glow-registry
node server.mjs
```

Default endpoints:
- `GET /health`
- `GET /glow_list.json`
- `GET /glow-list/compact`
- `POST /share`

Default port:
- `8787`

## Environment variables

- `PORT`
- `ASTRAL_GLOW_DATA_FILE`
- `ASTRAL_GLOW_PUBLIC_LIST_URL`
- `ASTRAL_GLOW_BOOTSTRAP_URL`
- `ASTRAL_GLOW_GITHUB_REPO`
- `ASTRAL_GLOW_GITHUB_BRANCH`
- `ASTRAL_GLOW_GITHUB_PATH`
- `ASTRAL_GLOW_GITHUB_TOKEN`

## Render setup

This repo now includes a root `render.yaml` for a free Render web service.

Recommended setup:
- create a new Blueprint service from this repo on Render
- keep the generated service URL and use it for Astral's `share-url`, for example `https://astral-glow-registry.onrender.com/share`
- use the same service's `https://.../glow_list.json` as `list-url`, or keep Astral pointed at the repo's raw `glow_list.json`
- add `ASTRAL_GLOW_GITHUB_TOKEN` in the Render dashboard so opt-ins are mirrored back into `jackforg/Astral-Addon`

Why the extra bootstrap variable exists:
- Render free web services can restart on a fresh filesystem
- `ASTRAL_GLOW_BOOTSTRAP_URL` lets the registry repopulate itself from the public GitHub list on cold start
- with GitHub mirroring enabled, the service stays in sync across restarts without needing a paid domain or persistent disk

If you skip the GitHub token:
- the Render service still works
- opt-ins are stored in the service's local runtime file until the service restarts
- users should then read `https://your-service.onrender.com/glow_list.json`, not the raw GitHub file

## GitHub mirror setup

If you want opt-in users to automatically appear in the repo-hosted public list:

1. Deploy this service somewhere you control.
2. Set:
   - `ASTRAL_GLOW_GITHUB_REPO=jackforg/Astral-Addon`
   - `ASTRAL_GLOW_GITHUB_BRANCH=main`
   - `ASTRAL_GLOW_GITHUB_PATH=glow_list.json`
   - `ASTRAL_GLOW_GITHUB_TOKEN=<token with contents:write on the repo>`
3. Set `ASTRAL_GLOW_PUBLIC_LIST_URL` to the public URL users should read, for example the raw GitHub URL.
4. Set `ASTRAL_GLOW_BOOTSTRAP_URL` to the same public list so the service can recover after a restart.
5. In Astral:
   - set `share-url` to `https://your-service.example/share`
   - leave `list-url` on the public GitHub raw file, or point it to `https://your-service.example/glow_list.json`

## Astral client flow

- Users enable `share-presence`
- Astral sends `uuid` + `username` to `/share`
- this service updates the registry
- the public list updates
- other Astral users fetch that list and glow matching UUIDs client-side

## Trust model

This registry is cosmetic and unauthenticated. Treat it as an opt-in user list, not proof of identity.
