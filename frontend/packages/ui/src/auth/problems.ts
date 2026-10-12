import { ApiError, isProblem } from '@jabiz/client'

/** Whether a failure was the link's own (invalid, expired, used, superseded): 422 TOKEN_INVALID. */
export function invalidLink(error: unknown): boolean {
  return isProblem(error, 'TOKEN_INVALID', 422)
}

/** The violations of a failure that concern the password fields; empty for anything else. */
export function passwordProblems(error: unknown, fields: readonly string[] = ['password', 'newPassword']): string[] {
  return error instanceof ApiError
    ? error.violations.filter((v) => v.field !== null && fields.includes(v.field)).map((v) => v.message)
    : []
}

/** Whether the server asks for a password it was not given (422 REQUIRED for `password`). */
export function passwordRequired(error: unknown): boolean {
  return error instanceof ApiError && error.status === 422
    && error.forField('password').some((v) => v.ruleCode === 'REQUIRED')
}

/** The server's message of a failure, or the general one. */
export function failure(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.violations.length > 0 ? error.display : fallback
}

/** The server's message of a refusal (its detail when it has no violations), or the general one for anything else. */
export function refusal(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.display : fallback
}
