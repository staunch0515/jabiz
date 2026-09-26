import type { Violation } from '../meta/types'

/** A failed API call: the HTTP status and the server's ProblemDetail with its violations. */
export class ApiError extends Error {
  readonly status: number
  readonly violations: Violation[]
  readonly problem: Record<string, unknown>

  constructor(status: number, problem: Record<string, unknown> | undefined) {
    const detail = problem?.detail ?? problem?.title
    super(typeof detail === 'string' && detail ? detail : `HTTP ${status}`)
    this.status = status
    this.problem = problem ?? {}
    this.violations = Array.isArray(problem?.violations) ? (problem.violations as Violation[]) : []
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
  return new ApiError(response.status, problem)
}
