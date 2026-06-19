/**
 * Push-token registry (arch §6.7).
 *
 * In-memory for M2 — registration + the suppression-by-connection mapping. The FCM/APNs
 * send paths and on-disk persistence land with the client phases (impl plan 6).
 */
export interface PushToken {
  token: string;
  platform: "fcm" | "apns";
  connId: string;
  registeredAt: string;
}

export class PushRegistry {
  private readonly byToken = new Map<string, PushToken>();

  register(connId: string, platform: "fcm" | "apns", token: string, at: string): void {
    this.byToken.set(token, { token, platform, connId, registeredAt: at });
  }
  unregister(token: string): void {
    this.byToken.delete(token);
  }
  list(): PushToken[] {
    return [...this.byToken.values()];
  }
}
