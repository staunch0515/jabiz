import { ApiError } from '@jabiz/client'

/** What the server said (its rules' texts in the user's language), else the error's message. */
export function reasonOf(error: unknown): string {
  if (error instanceof ApiError) return error.display
  return error instanceof Error ? error.message : String(error)
}
