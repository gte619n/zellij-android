/** Wire-envelope helpers (arch §6.1). */

/** Current timestamp as an ISO 8601 string. */
export function now(): string {
  return new Date().toISOString();
}
