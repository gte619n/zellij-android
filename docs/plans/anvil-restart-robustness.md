# Anvil daemon — restart / reset robustness

Hardening the daemon so a restart (launchd kickstart, crash, manual) always brings sessions
back to a usable state, and so no single bad session can take the daemon down.

## Failure modes this addresses (all observed in production)

1. **Crash-loop on startup** — one session that throws while loading crashed the whole daemon;
   launchd then crash-looped it.
2. **Post-teardown emit crash** — a killed session's agent turn was still draining and emitted an
   event after its state dir was removed → uncaught `ENOENT` crashed the daemon.
3. **Wedged worktree** — a `fresh-worktree` session whose worktree directory was missing after a
   restart loaded into a broken state (status idle, but `cwd` gone → all file/git ops fail).
4. **Frozen status** — a session caught mid-turn could come back showing `thinking`/`running_tool`
   with no live agent behind it.
5. **Memory/disk drift** — sessions held in memory weren't always persisted (and vice-versa), so a
   later restart could lose them.
6. **Corruptible state file** — `sessions.json` was written non-atomically; a crash mid-write could
   truncate it and lose every session.
7. **No graceful shutdown** — every restart hard-killed in-flight turns and risked inconsistent state.
8. **No recovery affordance** — a user with a stuck session had no way to un-stick it.

## Design

### Atomic, self-healing store (`session/store.ts`)
- `saveAll` writes `sessions.json.tmp` then `renameSync` over the real file (atomic on POSIX).
- `loadAll` on parse failure moves the bad file aside to `sessions.json.corrupt-<ts>` (kept for
  forensics) and returns `[]` rather than silently wiping.
- `listSessionDirs()` enumerates on-disk session state dirs for reconciliation/logging.

### Session disposal guard (`session/session.ts`)
- A `disposed` flag set on kill/archive/shutdown; `emit()` becomes a no-op once disposed, so a
  late-draining driver can't emit into a dead session (the general fix for failure mode 2).
- `emit()` wraps the sink + persist callbacks in try/catch — a broadcast or persist error is logged,
  never thrown into the agent turn loop.

### Worktree health + recovery (`session/worktree.ts`)
- `worktreeHealth(cwd, branch?)` → `ok | missing | not-a-worktree | wrong-branch`.
- `recreateWorktree(repoRoot, cwd, branch, base)` prunes stale registration then re-adds the
  worktree from the existing branch (or creates the branch off `base` if it's gone).

### Resilient restore (`session/supervisor.ts` `restore()`)
- Per-session `try/catch`: a session that fails to load is logged and **quarantined** (skipped), not
  fatal.
- Transient statuses (`thinking`/`running_tool`/`awaiting_permission`) → `idle` with an interrupted
  notice (existing behavior, kept).
- `fresh-worktree` sessions: check worktree health; if unhealthy, attempt `recreateWorktree`. On
  success emit a "worktree restored" notice; on failure emit a clear "use Reset" notice and leave the
  session idle (not spinning).
- Persist **once** at the end so disk == memory after load (fixes drift).
- Log a one-line summary: `restored N (recovered R worktrees, quarantined Q)`.

### `session.reset` (protocol + dispatch + http + supervisor + web)
- New `SessionResetCmd` and `POST /api/sessions/:id/reset` (REST parity so a native client can reset).
- `supervisor.reset(id)`: stop+drop any stale driver, clear watchers, kill the terminal, recover the
  worktree if needed, deny+clear any parked permission for the session, reset status to `idle`,
  persist, broadcast, emit a notice.
- Web: a **Reset** button in the git panel.

### Graceful shutdown (`main.ts` + supervisor)
- `supervisor.shutdown()` stops all drivers (reaps the `claude` child processes so they don't orphan
  across restarts) and flushes state.
- `main.ts` traps `SIGTERM`/`SIGINT` → `await shutdown()` → exit, with a 4s watchdog so a hung driver
  can't block exit past launchd's 5s kill window.
- **Verified launchd behavior** (`com.anvil.anvild`, `KeepAlive=true`): the restart is launchd's job,
  not ours — `kickstart -k` (what `service.sh restart` runs) always starts a fresh instance, and a
  crash (non-zero exit) is respawned by KeepAlive. launchd does **not** auto-respawn after a SIGTERM
  (clean or signaled) regardless of exit code, so the handler's exit code is irrelevant; its only job
  is the clean flush + child reaping. Because state is also persisted on every change, durability
  never depended on this handler.

## Tests (`test/unit`)
- store: atomic round-trip + corrupt file is backed up, not lost.
- restore: a malformed session entry is quarantined, the good ones still load.
- restore: a `fresh-worktree` session with a deleted worktree is recovered.
- reset: recovers a deleted worktree and resets a frozen status.
- session: `emit()` is a no-op after `dispose()`.
