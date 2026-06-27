# Shushly relay

A small Node + TypeScript service that sits between the Shushly Android app and OpenAI. The app sends
notification metadata; the relay classifies it as `alert` or `silent` and returns a structured result.
It exists so the OpenAI key never ships inside the app and so requests can be authenticated, rate
limited, and paused centrally.

## Requirements

- Node 22+

## Install

```
cd relay
npm install
```

## Configure

Copy `.env.example` to `.env` and fill in real values. `.env` is gitignored and must never be
committed. The relay auto-loads `.env` at startup (Node's built-in env-file support; no `dotenv`).

| Variable           | Default        | Meaning                                                        |
| ------------------ | -------------- | -------------------------------------------------------------- |
| `OPENAI_API_KEY`   | (none)         | OpenAI key. Read only from the environment; never logged.      |
| `OPENAI_MODEL`     | `gpt-4.1-mini` | Model used for classification.                                 |
| `DEVICE_TOKENS`    | (none)         | Comma-separated bearer tokens accepted from devices.           |
| `PORT`             | `8787`         | Listen port.                                                   |
| `RATE_PER_MIN`     | `20`           | Per-token requests per minute.                                 |
| `DEVICE_DAILY_CAP` | `200`          | Per-token requests per UTC day.                                |
| `GLOBAL_DAILY_CAP` | `500`          | All-token requests per UTC day.                                |
| `KILL_SWITCH`      | `0`            | `1` pauses classification (returns `silent`, no OpenAI call).  |

## Run

```
npm run dev          # tsx src/server.ts
npm run typecheck    # tsc --noEmit
npm run build        # tsc -> dist/
npm start            # node dist/server.js (after build)
```

On startup the relay logs a single `ready` line with the port, model, kill-switch state, and whether a
key/tokens are configured (presence and counts only, never the values).

## Endpoints

- `GET /health` — no auth. `200 {"ok":true}`.
- `GET /v1/auth-check` — bearer auth. `200 {"ok":true,"model":"<configured>"}`. No OpenAI call.
- `POST /v1/classify-notification` — bearer auth. Classifies one notification. Request and response
  contracts are defined in the task spec (schema version 1).

Status codes: `200` ok, `400` bad request, `401` unauthorized, `413` payload too large,
`429` rate limited, `502` upstream/OpenAI failure or invalid model output, `405` wrong method/path.

## Privacy

- The OpenAI key is read only from `process.env.OPENAI_API_KEY`. It is never hard-coded, logged, or
  written to disk by this code.
- Logs are one structured line per request and never include notification title/body, the user
  instruction, bearer tokens, or the key. Tokens appear only as a `sha256` fingerprint prefix
  (first 8 hex chars) for correlation.
- Notification text is truncated and stripped of control characters before classification, and is not
  persisted anywhere.

## Notes

- Rate/budget counters are in-memory and reset on restart (prototype scope).
- No database, deployment config, or admin UI is included here.
