# Astral Glow Registry

This is a small reference service for AstralGlow's opt-in presence sharing.

It does three things:
- accepts `POST /share` requests containing only `uuid` and `username`
- serves the public registry at `GET /glow_list.json`
- can optionally mirror that public registry back into a GitHub repo such as `jackforg/Astral-Addon`

## Why this exists

Astral's `share-presence` client setting is intentionally opt-in and transparent. The addon can send a user's UUID and username, but something on the server side still has to:
- store those opt-ins
- turn them into a public JSON list
- optionally keep a GitHub-hosted public copy in sync for easy inspection

This service is that missing piece.

## Data stored

The public registry contains:
- `uuid`
- `username`
- `source`
- `optedInAt`
- `lastSeenAt`

The service does not intentionally store chat, coordinates, inventory, server IPs, tokens, or account credentials.

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
- `ASTRAL_GLOW_GITHUB_REPO`
- `ASTRAL_GLOW_GITHUB_BRANCH`
- `ASTRAL_GLOW_GITHUB_PATH`
- `ASTRAL_GLOW_GITHUB_TOKEN`

## GitHub mirror setup

If you want opt-in users to automatically appear in the repo-hosted public list:

1. Deploy this service somewhere you control.
2. Set:
   - `ASTRAL_GLOW_GITHUB_REPO=jackforg/Astral-Addon`
   - `ASTRAL_GLOW_GITHUB_BRANCH=main`
   - `ASTRAL_GLOW_GITHUB_PATH=glow_list.json`
   - `ASTRAL_GLOW_GITHUB_TOKEN=<token with contents:write on the repo>`
3. Set `ASTRAL_GLOW_PUBLIC_LIST_URL` to the public URL users should read, for example the raw GitHub URL.
4. In Astral:
   - set `share-url` to `https://your-service.example/share`
   - leave `list-url` on the public GitHub raw file, or point it to `https://your-service.example/glow_list.json`

## Astral client flow

- Users enable `share-presence`
- Astral sends `uuid` + `username` to `/share`
- this service updates the registry
- the public list updates
- other Astral users fetch that list and glow matching UUIDs client-side

## Trust model

This registry is cosmetic and unauthenticated. It should be treated as an opt-in, user-submitted list, not a proof-of-identity system.
