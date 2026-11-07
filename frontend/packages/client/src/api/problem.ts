/** One reported problem, the same shape as the server's ProblemDetail violations. */
export interface Violation {
  field: string | null
  ruleCode: string
  message: string
  params?: Record<string, unknown>
}

/** A failed API call: the HTTP status and the server's ProblemDetail with its violations. */
export class ApiError extends Error {
  readonly status: number
  readonly violations: Violation[]
  readonly problem: Record<string, unknown>
  /**
   * The seconds the server asks to wait before trying again (its `Retry-After`, on 429 RATE_LIMITED and 503): the
   * entry pages count down and hold their button meanwhile (phase 17zk). Undefined when the answer did not say.
   */
  readonly retryAfter?: number

  constructor(status: number, problem: Record<string, unknown> | undefined, retryAfter?: number) {
    const detail = problem?.detail ?? problem?.title
    super(typeof detail === 'string' && detail ? detail : `HTTP ${status}`)
    this.status = status
    this.problem = problem ?? {}
    this.violations = Array.isArray(problem?.violations) ? (problem.violations as Violation[]) : []
    this.retryAfter = retryAfter
  }

  /** The violations of one field, and those without a field (shown for the whole form). */
  forField(field: string): Violation[] {
    return this.violations.filter((v) => v.field === field)
  }

  get general(): Violation[] {
    return this.violations.filter((v) => !v.field)
  }

  /** The first message worth showing: the violations' messages, else the detail. */
  get display(): string {
    return this.violations.length > 0 ? this.violations.map((v) => v.message).join(' ') : this.message
  }
}

export async function toApiError(response: Response, body?: unknown): Promise<ApiError> {
  let problem = body as Record<string, unknown> | undefined
  if (problem === undefined) {
    try {
      problem = (await response.clone().json()) as Record<string, unknown>
    } catch {
      problem = undefined
    }
  }
  return new ApiError(response.status, problem, retryAfterSeconds(response.headers.get('Retry-After')))
}

/** The longest wait taken from `Retry-After`: a longer one is cut to this (a form never waits for hours). */
const MAX_RETRY_AFTER = 3600

/** An IMF-fixdate, the HTTP-date form servers send: `Sun, 06 Nov 1994 08:49:37 GMT`. */
const IMF_FIXDATE = /^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \d{2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4} \d{2}:\d{2}:\d{2} GMT$/

/**
 * `Retry-After` in seconds: non-negative whole seconds or an IMF-fixdate, at most an hour; undefined when absent or
 * anything else.
 */
export function retryAfterSeconds(header: string | null, now: number = Date.now()): number | undefined {
  const value = header?.trim()
  if (!value) return undefined
  let seconds: number
  if (/^\d{1,10}$/.test(value)) {
    seconds = Number(value)
  } else if (IMF_FIXDATE.test(value)) {
    const at = Date.parse(value)
    if (Number.isNaN(at)) return undefined
    seconds = Math.max(0, Math.ceil((at - now) / 1000))
  } else {
    return undefined
  }
  return Math.min(seconds, MAX_RETRY_AFTER)
}
