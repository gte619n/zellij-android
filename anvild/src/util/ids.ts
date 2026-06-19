/** Stable, prefixed ids (e.g. "conn_…", "sess_…"). */
export function newId(prefix: string): string {
  return `${prefix}_${crypto.randomUUID()}`;
}
