import { rmSync } from "node:fs";
import { join } from "node:path";
import type { GitStatus, Worktree } from "@protocol";

/** Run git in `cwd`, capturing output. */
function git(args: string[], cwd: string): { code: number; stdout: string; stderr: string } {
  const r = Bun.spawnSync(["git", ...args], { cwd, stdout: "pipe", stderr: "pipe" });
  return { code: r.exitCode, stdout: r.stdout.toString(), stderr: r.stderr.toString() };
}

export interface CreatedWorktree {
  worktree: Worktree;
  cwd: string;
}

/**
 * Create a fresh git worktree off `base` (arch §5) on branch `branch` (the session name),
 * checked out at `<worktreeRoot>/<sessionId>`. Throws if the branch already exists so the
 * caller can ask for a different name.
 */
export function createWorktree(
  repoRoot: string,
  base: string,
  branch: string,
  worktreeRoot: string,
  sessionId: string,
): CreatedWorktree {
  const cwd = join(worktreeRoot, sessionId);
  const r = git(["worktree", "add", "-b", branch, cwd, base], repoRoot);
  if (r.code !== 0) {
    throw new Error(r.stderr.trim() || r.stdout.trim() || "git worktree add failed");
  }
  return { worktree: { repoRoot, branch, base }, cwd };
}

/**
 * Remove a worktree (arch §5 kill cleanup): `git worktree remove --force` (rmtree + prune
 * fallback), then delete the branch so the session name is reusable.
 */
export function removeWorktree(repoRoot: string, cwd: string, branch?: string): void {
  const r = git(["worktree", "remove", "--force", cwd], repoRoot);
  if (r.code !== 0) {
    rmSync(cwd, { recursive: true, force: true });
    git(["worktree", "prune"], repoRoot);
  }
  if (branch) git(["branch", "-D", branch], repoRoot); // free the name (best-effort)
}

/** Best-effort git state for the worktree panel (arch §8); undefined if `cwd` isn't a repo. */
export function gitStatus(cwd: string): GitStatus | undefined {
  const branch = git(["branch", "--show-current"], cwd);
  if (branch.code !== 0) return undefined;

  const dirty = git(["status", "--porcelain"], cwd).stdout.split("\n").filter((l) => l.trim().length > 0);

  let ahead = 0;
  let behind = 0;
  const ab = git(["rev-list", "--left-right", "--count", "@{u}...HEAD"], cwd);
  if (ab.code === 0) {
    const parts = ab.stdout.trim().split(/\s+/).map(Number);
    behind = parts[0] ?? 0;
    ahead = parts[1] ?? 0;
  }

  const diffstat = git(["diff", "--stat"], cwd).stdout.split("\n").filter((l) => l.trim().length > 0);

  return { branch: branch.stdout.trim(), ahead, behind, dirtyFileCount: dirty.length, diffstat };
}
