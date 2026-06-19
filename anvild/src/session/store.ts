import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { Session as SessionData } from "@protocol";

export interface PersistedSession {
  data: SessionData;
  lastSeq: number;
}

/**
 * Durable session registry (arch §5). `sessions.json` holds the rows + `lastSeq`; per-
 * session dirs hold future event logs (M6) and worktrees live under `worktrees/`.
 */
export class SessionStore {
  private readonly file: string;

  constructor(private readonly stateDir: string) {
    this.file = join(stateDir, "sessions.json");
    mkdirSync(join(stateDir, "sessions"), { recursive: true });
    mkdirSync(join(stateDir, "worktrees"), { recursive: true });
  }

  loadAll(): PersistedSession[] {
    if (!existsSync(this.file)) return [];
    try {
      const parsed = JSON.parse(readFileSync(this.file, "utf8")) as { sessions?: PersistedSession[] };
      return parsed.sessions ?? [];
    } catch {
      return [];
    }
  }

  saveAll(sessions: PersistedSession[]): void {
    writeFileSync(this.file, JSON.stringify({ sessions }, null, 2));
  }

  sessionDir(id: string): string {
    return join(this.stateDir, "sessions", id);
  }
  worktreeRoot(): string {
    return join(this.stateDir, "worktrees");
  }
}
