/**
 * Whether a process's output says that its change waits for approval (`approval: 'PENDING'`): the protected grants of
 * security (docs/design/10-security.md section 16, decision D48) and application processes that ask for an approval
 * answer so. Nothing has taken effect yet.
 */
export function pendingApproval(output: unknown): boolean {
  return (
    typeof output === 'object' && output !== null && (output as { approval?: unknown }).approval === 'PENDING'
  )
}
