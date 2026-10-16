import { ApiError, i18n } from '@jabiz/client'
import { APP } from '../messages'
import { CODE } from '../qb/ids'
import { problemText } from '../qb/problems'

/*
 * A hot quiz's queue (platform decision D45): when many people start or submit the same quiz at once, the server
 * queues them before the quiz's lock and answers the overflow at once with 503 `PROCESS_BUSY` and `Retry-After`. The
 * App then says it is busy and tries again by itself, never sooner than the server asked, with the same
 * `Idempotency-Key` (the caller's closure keeps it): a retry of a call that went through returns its answer, and an
 * attempt is finished once whatever the key. It stops when the page goes away (the signal), when the caller says a
 * retry is no use any more (a submission past its time), and when the waits would pass a minute in all.
 */

/** Tries in all (the first and the retries). */
export const BUSY_ATTEMPTS = 6
/** The back-off doubles from the first delay up to the longest. */
export const BUSY_FIRST_DELAY_MS = 1_000
export const BUSY_LONGEST_DELAY_MS = 8_000
/**
 * The waits of one call's retries together at most (the server's `Retry-After` can be up to 30 s): without it six
 * tries could wait about three minutes. With the back-off alone the waits come to about 20–25 s.
 */
export const BUSY_TOTAL_WAIT_MS = 60_000

/** Whether the server's queue before a hot quiz turned the call away (worth trying again soon). */
export function isBusy(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 503
    && error.violations.some((v) => v.ruleCode === CODE.processBusy)
}

/** What to tell the taker about a failed start or submission: still busy after the retries, or the usual text. */
export function playProblemText(error: unknown): string {
  return isBusy(error) ? i18n.t('play.busyGaveUp', { ns: APP }) : problemText(error)
}

/** The server's `Retry-After` in milliseconds (the problem carries it as `retryAfter`, in seconds); 0 when absent. */
export function retryAfterMs(error: ApiError): number {
  const seconds = Number(error.problem.retryAfter)
  return Number.isFinite(seconds) && seconds > 0 ? seconds * 1_000 : 0
}

/**
 * The wait before try `attempt + 1` after a busy answer: the doubling back-off, never less than the server's
 * `Retry-After`, plus up to a quarter more at random so that the turned-away do not all come back at once.
 */
export function busyDelay(error: ApiError, attempt: number, random: () => number = Math.random): number {
  const backoff = Math.min(BUSY_LONGEST_DELAY_MS, BUSY_FIRST_DELAY_MS * 2 ** Math.max(0, attempt - 1))
  const wait = Math.max(retryAfterMs(error), backoff)
  return Math.round(wait * (1 + 0.25 * random()))
}

export interface BusyRetryOptions {
  /** Told `true` before each wait for a retry and `false` once the call is over (answered, given up or aborted). */
  onBusy?: (busy: boolean) => void
  /** Aborted (the page went away): no further try, the wait ends at once, and the abort's reason is thrown. */
  signal?: AbortSignal
  /** Asked before each retry: false gives the busy answer back (for example a submission past its time). */
  shouldRetry?: () => boolean
  attempts?: number
  totalWaitMs?: number
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>
  random?: () => number
}

function aborted(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException('Aborted', 'AbortError')
}

/** Waits `ms`, or less when the signal aborts (then rejects with its reason). */
export function pause(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    if (signal?.aborted) {
      reject(aborted(signal))
      return
    }
    const onAbort = () => {
      clearTimeout(timer)
      reject(aborted(signal!))
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

/**
 * Runs `call` and, while the server answers busy, waits and runs it again (at most `attempts` times in all and
 * `totalWaitMs` of waiting). Any other failure, and the last busy answer, are the caller's; an abort throws its reason.
 * `call` must send the same `Idempotency-Key` every time.
 */
export async function retryWhenBusy<T>(call: () => Promise<T>, options: BusyRetryOptions = {}): Promise<T> {
  const attempts = options.attempts ?? BUSY_ATTEMPTS
  const totalWaitMs = options.totalWaitMs ?? BUSY_TOTAL_WAIT_MS
  const sleep = options.sleep ?? pause
  const { signal } = options
  let waited = 0
  try {
    for (let attempt = 1; ; attempt++) {
      if (signal?.aborted) throw aborted(signal)
      try {
        return await call()
      } catch (error) {
        if (!isBusy(error) || attempt >= attempts || signal?.aborted) throw error
        if (options.shouldRetry && !options.shouldRetry()) throw error
        const delay = busyDelay(error, attempt, options.random)
        if (waited + delay > totalWaitMs) throw error
        waited += delay
        options.onBusy?.(true)
        await sleep(delay, signal)
      }
    }
  } finally {
    options.onBusy?.(false)
  }
}
