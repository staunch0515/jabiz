/**
 * Counts one visit of an anonymous or signed-in visitor on a public page (docs/design/15-public-access.md section 10;
 * decision D65): `POST /api/public/counters/{counter}/{key}`, without a body or credentials. The server counts each
 * visitor once a day per key, and only keys visible in the counter's public dataset; it answers every request alike,
 * so nothing is returned. Sent with `navigator.sendBeacon`, which survives leaving the page (a click on an outbound
 * link), else `fetch` with `keepalive`; a failure is ignored, never shown.
 */
export function countPublic(counter: string, key: string): void {
  if (typeof window === 'undefined' || !counter || !key) return
  const url = `${window.location.origin}/api/public/counters/${encodeURIComponent(counter)}/${encodeURIComponent(key)}`
  try {
    if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function' && navigator.sendBeacon(url)) {
      return
    }
  } catch {
    // Some browsers refuse a beacon (size, policy): fall back to fetch.
  }
  try {
    void fetch(url, { method: 'POST', keepalive: true, credentials: 'omit' }).catch(() => undefined)
  } catch {
    // A count is never worth an error.
  }
}
