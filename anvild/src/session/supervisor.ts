import { mkdirSync, rmSync } from "node:fs";
import {
  PROTOCOL_VERSION,
  type AutonomyPolicy,
  type Model,
  type ServerEvent,
  type Session as SessionData,
  type SessionCreateCmd,
  type SessionListEvent,
} from "@protocol";
import { now } from "../util/envelope";
import { newId } from "../util/ids";
import type { ConnectionRegistry } from "../server/registry";
import { Session } from "./session";
import { SessionStore } from "./store";
import { createWorktree, gitStatus, removeWorktree } from "./worktree";

/** A client command that can't be honored (bad args, no such session). → command.error. */
export class BadCommand extends Error {}

export interface SupervisorConfig {
  stateDir: string;
}

/**
 * The session registry + lifecycle owner (arch §5). Creates (existing-dir or fresh-
 * worktree), persists, restores on startup, and kills (process-group reap + worktree
 * cleanup). Broadcasts global `session.*` events; session-scoped events flow through each
 * `Session`'s `emit` to attached connections.
 */
export class Supervisor {
  private readonly store: SessionStore;
  private readonly sessions = new Map<string, Session>();

  constructor(cfg: SupervisorConfig, private readonly registry: ConnectionRegistry) {
    this.store = new SessionStore(cfg.stateDir);
    this.restore();
  }

  list(): SessionData[] {
    return [...this.sessions.values()].map((s) => s.data);
  }
  get(id: string): Session | undefined {
    return this.sessions.get(id);
  }
  sessionListEvent(): SessionListEvent {
    return { v: PROTOCOL_VERSION, type: "session.list", ts: now(), sessions: this.list() };
  }

  create(cmd: SessionCreateCmd): Session {
    const id = newId("sess");
    let cwd: string;
    let worktree: SessionData["worktree"];

    if (cmd.source === "fresh-worktree") {
      if (!cmd.repoRoot) throw new BadCommand("repoRoot is required for a fresh-worktree session");
      const created = createWorktree(cmd.repoRoot, cmd.base ?? "HEAD", slugify(cmd.title ?? "session"), this.store.worktreeRoot(), id);
      cwd = created.cwd;
      worktree = created.worktree;
    } else {
      if (!cmd.cwd) throw new BadCommand("cwd is required for an existing-dir session");
      cwd = cmd.cwd;
    }

    mkdirSync(this.store.sessionDir(id), { recursive: true });
    const data: SessionData = {
      id,
      title: cmd.title ?? deriveTitle(cwd),
      cwd,
      source: cmd.source,
      worktree,
      git: gitStatus(cwd),
      model: cmd.model ?? "opus",
      autonomy: cmd.autonomy ?? "mostly-autonomous",
      status: "idle",
      createdAt: now(),
      lastActivityAt: now(),
      usage: { inputTokens: 0, outputTokens: 0, turns: 0 },
    };

    const session = this.wrap(data, 0);
    this.sessions.set(id, session);
    this.persist();
    return session; // dispatch announces session.created (creator gets the cid; others via registry)
  }

  setModel(id: string, model: Model): void {
    const s = this.require(id);
    s.data.model = model;
    s.data.lastActivityAt = now();
    this.persist();
    this.broadcastUpdated(s.data);
  }
  setAutonomy(id: string, policy: AutonomyPolicy): void {
    const s = this.require(id);
    s.data.autonomy = policy;
    s.data.lastActivityAt = now();
    this.persist();
    this.broadcastUpdated(s.data);
  }

  async kill(id: string): Promise<void> {
    const s = this.require(id);
    await s.stop(); // reap the process group (no-op until M5 attaches one)
    if (s.data.source === "fresh-worktree" && s.data.worktree) {
      removeWorktree(s.data.worktree.repoRoot, s.data.cwd);
    }
    rmSync(this.store.sessionDir(id), { recursive: true, force: true });
    this.sessions.delete(id);
    this.persist();
    this.registry.toAll({ v: PROTOCOL_VERSION, type: "session.deleted", ts: now(), sessionId: id });
  }

  // ── internals ──────────────────────────────────────────────────────────────

  private wrap(data: SessionData, lastSeq: number): Session {
    return new Session(
      data,
      lastSeq,
      (sessionId, event) => this.registry.toAttached(sessionId, event),
      () => this.persist(),
    );
  }

  private restore(): void {
    const transient: SessionData["status"][] = ["thinking", "running_tool", "awaiting_permission"];
    for (const p of this.store.loadAll()) {
      // a daemon restart means no live agent process — reset transient states to idle
      if (transient.includes(p.data.status)) p.data.status = "idle";
      this.sessions.set(p.data.id, this.wrap(p.data, p.lastSeq));
    }
  }

  private persist(): void {
    this.store.saveAll([...this.sessions.values()].map((s) => ({ data: s.data, lastSeq: s.lastSeq })));
  }

  private require(id: string): Session {
    const s = this.sessions.get(id);
    if (!s) throw new BadCommand(`no such session: ${id}`);
    return s;
  }

  private broadcastUpdated(data: SessionData): void {
    this.registry.toAll({ v: PROTOCOL_VERSION, type: "session.updated", ts: now(), session: data });
  }
}

function slugify(s: string): string {
  return (
    s
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 32) || "session"
  );
}
function deriveTitle(cwd: string): string {
  return cwd.split("/").filter(Boolean).pop() ?? "session";
}
