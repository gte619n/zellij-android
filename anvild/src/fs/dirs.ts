import { existsSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, parse, resolve } from "node:path";
import type { DirEntry } from "@protocol";

/**
 * List subdirectories of `rawPath` (default: the daemon user's home) for the session-create
 * directory picker. Pre-session, so NOT worktree-scoped — the user is browsing their own
 * machine to choose a project (single-user; the tailnet is the access boundary, arch §4).
 * Hidden dirs are skipped to keep the picker clean.
 */
export function listDirs(rawPath?: string): { path: string; parent?: string; entries: DirEntry[] } {
  const path = resolve(rawPath && rawPath.trim().length > 0 ? rawPath : homedir());
  const entries: DirEntry[] = [];
  for (const d of readdirSync(path, { withFileTypes: true })) {
    if (!d.isDirectory() || d.name.startsWith(".")) continue;
    const full = join(path, d.name);
    entries.push({ name: d.name, path: full, isDir: true, isRepo: existsSync(join(full, ".git")) });
  }
  entries.sort((a, b) => a.name.localeCompare(b.name));
  const parent = path === parse(path).root ? undefined : dirname(path);
  return { path, parent, entries };
}
