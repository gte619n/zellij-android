# anvild

The Anvil daemon — supervises Claude Code sessions and serves the Anvil protocol
(`../docs/plans/anvil-protocol.ts`, symlinked here as `protocol.ts`) over Tailscale.

See the plans in `../docs/plans/`: `anvil-native-architecture.md` (design),
`anvil-impl-1-daemon-core.md` (this component), `anvil-impl-INDEX.md` (all components).

## Run

```sh
bun install
# Auth (arch §3): subscription OAuth token, and NO metered API key in the env.
export CLAUDE_CODE_OAUTH_TOKEN="$(claude setup-token)"   # one-time
bun run start          # http://localhost:7701  (ws: /ws · health: /api/health)
bun run dev            # watch mode
bun test               # unit + integration (no token/network needed — uses a mock)
bun run typecheck
```

The daemon **refuses to start** if `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` is set
(they outrank the OAuth token and would meter billing — arch §3).

## Milestone status (impl plan 1)

- [x] **M1** — skeleton, auth/billing guard (§3), `GET /api/health` (`subscriptionAuthOk`)
- [x] **M2** — WS server, envelope dispatch, `cid` ack/error correlation, push register/unregister
- [x] **M3** — session registry + persistence (`sessions.json`) + per-session `seq`; `session.list` on connect; create/attach/detach/kill/set_model/set_autonomy
- [x] **M4** — fresh-worktree create (`git worktree add`) + process-group kill/reap (`detached` spawn, SIGTERM→SIGKILL group), worktree removal on kill
- [ ] **M5** — Agent SDK streaming driver (`SDKMessage` → `ServerEvent`) — **needs a real `CLAUDE_CODE_OAUTH_TOKEN`**
- [ ] **M6** — event-log persistence + resume / snapshot
- [ ] **M7** — permissions + autonomy + danger list
- [ ] **M8** — budget tracker + warn/soft-stop

## Layout

`src/auth` guard · `src/server` (http/dispatch/registry) · `src/push` registry ·
`src/budget` tracker (stub) · `src/session` `src/agent` `src/eventlog` `src/render` (M3+).
`bun:sqlite`/files for state land with M3.
