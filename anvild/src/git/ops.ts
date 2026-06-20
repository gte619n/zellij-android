/**
 * Git / gh operations on a session worktree (arch §8). All shell out and return combined
 * stdout+stderr so the UI can show exactly what happened. PR ops use the `gh` CLI.
 */
function run(cmd: string[], cwd: string): { code: number; out: string } {
  const r = Bun.spawnSync(cmd, { cwd, stdout: "pipe", stderr: "pipe" });
  const out = `${r.stdout.toString()}${r.stderr.toString()}`.trim();
  return { code: r.exitCode, out };
}

const CAP = 60_000;
const cap = (s: string): string => (s.length > CAP ? `${s.slice(0, CAP)}\n… (truncated)` : s);

export function diff(cwd: string): { ok: boolean; output: string } {
  const r = run(["git", "--no-pager", "diff", "HEAD"], cwd);
  return { ok: r.code === 0, output: cap(r.out) || "(no uncommitted changes)" };
}

export function commit(cwd: string, message: string): { ok: boolean; output: string } {
  run(["git", "add", "-A"], cwd);
  const r = run(["git", "commit", "-m", message], cwd);
  return { ok: r.code === 0, output: r.out || (r.code === 0 ? "committed" : "nothing to commit") };
}

export function push(cwd: string, branch: string): { ok: boolean; output: string } {
  const r = run(["git", "push", "-u", "origin", branch], cwd);
  return { ok: r.code === 0, output: r.out || "pushed" };
}

export function createPr(cwd: string, title: string, body: string): { ok: boolean; output: string; url?: string } {
  const r = run(["gh", "pr", "create", "--title", title, "--body", body], cwd);
  const url = r.out.match(/https?:\/\/\S+/)?.[0];
  return { ok: r.code === 0, output: r.out, url };
}

export function mergePr(cwd: string, method: "merge" | "squash" | "rebase"): { ok: boolean; output: string } {
  const flag = method === "squash" ? "--squash" : method === "rebase" ? "--rebase" : "--merge";
  const r = run(["gh", "pr", "merge", flag, "--delete-branch"], cwd);
  return { ok: r.code === 0, output: r.out || "merged" };
}

/** PR state for the current branch via `gh` (network); undefined if there's no PR. */
export function prStatus(cwd: string): { state?: "open" | "merged" | "closed"; url?: string } {
  const r = run(["gh", "pr", "view", "--json", "state,url"], cwd);
  if (r.code !== 0) return {};
  try {
    const j = JSON.parse(r.out) as { state?: string; url?: string };
    const s = (j.state ?? "").toLowerCase();
    return { state: s === "open" || s === "merged" || s === "closed" ? s : undefined, url: j.url };
  } catch {
    return {};
  }
}

/** Best-effort delete of the remote branch (for abandon/cleanup). */
export function deleteRemoteBranch(cwd: string, branch: string): void {
  run(["git", "push", "origin", "--delete", branch], cwd);
}
