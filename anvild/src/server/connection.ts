/** Per-WebSocket-connection state (arch §6.2). Lives in `ServerWebSocket.data`. */
export interface ConnState {
  id: string;
  /** sessionIds this connection is attached to (used for live broadcast / resume). */
  attached: Set<string>;
}
