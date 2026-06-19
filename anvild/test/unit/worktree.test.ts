import { test, expect } from "bun:test";
import { mkdtempSync, writeFileSync, existsSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createWorktree, removeWorktree, gitStatus } from "../../src/session/worktree";

function git(args: string[], cwd: string) {
  return Bun.spawnSync(["git", ...args], { cwd });
}

function makeRepo(): string {
  const repo = mkdtempSync(join(tmpdir(), "anvil-repo-"));
  git(["init", "-q"], repo);
  git(["config", "user.email", "t@example.com"], repo);
  git(["config", "user.name", "Test"], repo);
  writeFileSync(join(repo, "README.md"), "hello\n");
  git(["add", "."], repo);
  git(["commit", "-q", "-m", "init"], repo);
  return repo;
}

test("create + remove a fresh worktree off HEAD", () => {
  const repo = makeRepo();
  const wtRoot = mkdtempSync(join(tmpdir(), "anvil-wt-"));

  const created = createWorktree(repo, "HEAD", "my-task", wtRoot, "sess_abcd1234");
  expect(existsSync(created.cwd)).toBe(true);
  expect(existsSync(join(created.cwd, "README.md"))).toBe(true);
  expect(created.worktree.branch).toBe("my-task");
  expect(created.worktree.repoRoot).toBe(repo);

  // gitStatus resolves the worktree branch
  const status = gitStatus(created.cwd);
  expect(status?.branch).toBe(created.worktree.branch);
  expect(status?.dirtyFileCount).toBe(0);

  removeWorktree(repo, created.cwd);
  expect(existsSync(created.cwd)).toBe(false);

  rmSync(repo, { recursive: true, force: true });
  rmSync(wtRoot, { recursive: true, force: true });
});

test("gitStatus returns undefined outside a repo", () => {
  const notRepo = mkdtempSync(join(tmpdir(), "anvil-norepo-"));
  expect(gitStatus(notRepo)).toBeUndefined();
  rmSync(notRepo, { recursive: true, force: true });
});
