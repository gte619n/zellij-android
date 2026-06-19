import type { ServerWebSocket } from "bun";
import type { ServerEvent } from "@protocol";
import type { ConnState } from "./connection";

/**
 * Tracks open connections and fans events out (arch §6.2):
 *  - `toAll` for global `session.*` events (optionally excluding the originating conn),
 *  - `toAttached` for session-scoped events, only to connections attached to that session.
 */
export class ConnectionRegistry {
  private readonly conns = new Set<ServerWebSocket<ConnState>>();

  add(ws: ServerWebSocket<ConnState>): void {
    this.conns.add(ws);
  }
  remove(ws: ServerWebSocket<ConnState>): void {
    this.conns.delete(ws);
  }
  all(): ServerWebSocket<ConnState>[] {
    return [...this.conns];
  }

  toAll(event: ServerEvent, exceptConnId?: string): void {
    const json = JSON.stringify(event);
    for (const ws of this.conns) {
      if (ws.data.id !== exceptConnId) ws.send(json);
    }
  }

  toAttached(sessionId: string, event: ServerEvent): void {
    const json = JSON.stringify(event);
    for (const ws of this.conns) {
      if (ws.data.attached.has(sessionId)) ws.send(json);
    }
  }
}
