/** A Web Locks stand-in for tests: one holder per name at a time, the others queued in order. */
export function fakeLocks() {
  const tails = new Map<string, Promise<unknown>>()
  const requested: string[] = []
  return {
    requested,
    request<T>(name: string, callback: () => Promise<T>): Promise<T> {
      requested.push(name)
      const run = (tails.get(name) ?? Promise.resolve()).then(() => callback())
      tails.set(name, run.catch(() => undefined))
      return run
    },
  }
}

/** Gives the browser Web Locks (or takes them away, with undefined). */
export function withLocks(locks: ReturnType<typeof fakeLocks> | undefined) {
  Object.defineProperty(navigator, 'locks', { value: locks, configurable: true })
}

/** A JSON response. */
export function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

/** The address of what fetch was given. */
export function urlOf(input: RequestInfo | URL): string {
  return typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
}
