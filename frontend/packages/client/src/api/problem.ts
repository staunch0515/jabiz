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

/** `Retry-After` in seconds: a number of seconds or an HTTP date; undefined when absent or unreadable. */
export function retryAfterSeconds(header: string | null, now: number = Date.now()): number | undefined {
  const value = header?.trim()
  if (!value) return undefined
  if (/^\d+$/.test(value)) return Number(value)
  const at = Date.parse(value)
  return Number.isNaN(at) ? undefined : Math.max(0, Math.ceil((at - now) / 1000))
}
