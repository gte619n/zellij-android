import { spawn, type ChildProcess } from "node:child_process";

/**
 * Process-group spawn + reap (arch §5, discipline from commit da870d5).
 *
 * The agent (and the tool subprocesses it spawns) run in their OWN process group, so a
 * single signal to the group reaps the whole tree — no orphaned grandchildren (the bug
 * that fuelled the old duplicate-server storm). `detached: true` makes the child a group
 * leader on POSIX, so `pgid === pid` and `process.kill(-pgid, …)` signals the group.
 */
export interface Group {
  pid: number;
  pgid: number;
  child: ChildProcess;
  exited: Promise<number | null>;
}

export interface SpawnOptions {
  cwd?: string;
  env?: Record<string, string | undefined>;
}

export function spawnInGroup(cmd: string, args: string[], opts: SpawnOptions = {}): Group {
  const child = spawn(cmd, args, {
    cwd: opts.cwd,
    env: opts.env,
    detached: true, // new process group; child is the leader
    stdio: "ignore",
  });
  const pid = child.pid;
  if (pid === undefined) throw new Error(`failed to spawn '${cmd}'`);
  const exited = new Promise<number | null>((resolve) => child.once("exit", (code) => resolve(code)));
  return { pid, pgid: pid, child, exited };
}

/** True iff any process remains in the group. */
export function groupAlive(pgid: number): boolean {
  try {
    process.kill(-pgid, 0); // signal 0 = existence check
    return true;
  } catch {
    return false;
  }
}

/** SIGTERM the group, wait up to `graceMs`, then SIGKILL. */
export async function killGroup(pgid: number, graceMs = 2000): Promise<void> {
  try {
    process.kill(-pgid, "SIGTERM");
  } catch {
    return; // already gone
  }
  const start = Date.now();
  while (Date.now() - start < graceMs) {
    if (!groupAlive(pgid)) return;
    await delay(50);
  }
  try {
    process.kill(-pgid, "SIGKILL");
  } catch {
    /* raced to exit between the check and the signal */
  }
}

const delay = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));
