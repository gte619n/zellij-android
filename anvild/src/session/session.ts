import { PROTOCOL_VERSION, type ServerEvent, type Session as SessionData, type SessionStatus } from "@protocol";
import { now } from "../util/envelope";
import { killGroup, type Group } from "./procgroup";

/** The session-scoped subset of ServerEvent (carries `sessionId` + `seq`). */
type SessionScopedEvent = Extract<ServerEvent, { sessionId: string; seq: number }>;
type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never;
/** What `emit` accepts — a session-scoped event minus the fields the session fills in. */
export type SessionEventBody = DistributiveOmit<SessionScopedEvent, "v" | "ts" | "sessionId" | "seq">;

export type EventSink = (sessionId: string, event: ServerEvent) => void;

/**
 * One live session (arch §5). Owns the per-session monotonic `seq` counter and the single
 * `emit()` that assigns `seq` → (M6: appends to the event log) → broadcasts. This is the
 * ONLY place `seq` is minted, which guarantees per-session monotonicity (arch §6.1).
 */
export class Session {
  private nextSeq: number;
  private group: Group | undefined;

  constructor(
    public data: SessionData,
    lastSeq: number,
    private readonly sink: EventSink,
    private readonly onChange: () => void,
  ) {
    this.nextSeq = lastSeq + 1;
  }

  get id(): string {
    return this.data.id;
  }
  get lastSeq(): number {
    return this.nextSeq - 1;
  }

  /** Mint `seq`, broadcast to attached connections, mark the session dirty for persistence. */
  emit(body: SessionEventBody): ServerEvent {
    const seq = this.nextSeq++;
    const event = { ...body, v: PROTOCOL_VERSION, ts: now(), sessionId: this.data.id, seq } as ServerEvent;
    this.sink(this.data.id, event);
    this.onChange();
    return event;
  }

  setStatus(status: SessionStatus): void {
    this.data.status = status;
    this.data.lastActivityAt = now();
    this.emit({ type: "status", status });
  }

  /** Attach the agent's process group (M5) so kill can reap it. */
  attachGroup(group: Group): void {
    this.group = group;
  }

  /** Reap the process group, if any (arch §5). */
  async stop(): Promise<void> {
    if (this.group) {
      await killGroup(this.group.pgid);
      this.group = undefined;
    }
  }
}
