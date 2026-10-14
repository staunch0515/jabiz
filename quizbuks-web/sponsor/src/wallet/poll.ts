/**
 * Bounded polling of a record that a background job or Stripe's notice changes shortly (docs/quizbuks/plans/
 * QP-pages.md section 10: the checkout opens in 1–3 seconds, Stripe's notice may take longer). The waits grow from a
 * second to {@link MAX_WAIT_MS}; after `timeoutMs` the page stops asking and says it will update later. The browser
 * only reads: nothing is decided or credited by polling.
 */

/** The waits between reads, then the last one repeated. */
export const WAITS_MS = [1_000, 1_000, 2_000, 2_000, 3_000] as const
export const MAX_WAIT_MS = 5_000

export type PollOutcome<T> =
  | { kind: 'done'; value: T }
  | { kind: 'timeout'; value: T | undefined }
  | { kind: 'aborted' }

export interface PollOptions<T> {
  /** Reads the record once; a failed read counts as "not yet" and is tried again. */
  read: () => Promise<T>
  /** Whether the value read is final. */
  done: (value: T) => boolean
  /** Told every value read (the page shows the latest). */
  onValue?: (value: T) => void
  /** How long to keep asking, from the first read. */
  timeoutMs: number
  signal?: AbortSignal
  /** For tests. */
  now?: () => number
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>
}

/** The wait before the next read after `reads` reads. */
export function waitAfter(reads: number): number {
  return WAITS_MS[reads - 1] ?? MAX_WAIT_MS
}

function defaultSleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms)
    signal?.addEventListener('abort', () => {
      clearTimeout(timer)
      resolve()
    }, { once: true })
  })
}

/** Reads until the value is final, the time is up or the caller gives up. */
export async function pollUntil<T>(options: PollOptions<T>): Promise<PollOutcome<T>> {
  const now = options.now ?? Date.now
  const sleep = options.sleep ?? defaultSleep
  const until = now() + options.timeoutMs
  let latest: T | undefined
  for (let reads = 1; ; reads++) {
    if (options.signal?.aborted) return { kind: 'aborted' }
    try {
      const value = await options.read()
      if (options.signal?.aborted) return { kind: 'aborted' }
      latest = value
      options.onValue?.(value)
      if (options.done(value)) return { kind: 'done', value }
    } catch {
      // Offline for a moment, or the server busy: ask again until the time is up.
      if (options.signal?.aborted) return { kind: 'aborted' }
    }
    const wait = waitAfter(reads)
    if (now() + wait > until) return { kind: 'timeout', value: latest }
    await sleep(wait, options.signal)
  }
}
