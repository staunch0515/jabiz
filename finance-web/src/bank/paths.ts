/** The bank pages' paths (outside the platform's own, decision D22). */
export const MATCHING_PATH = '/bank/matching'
export const RECONCILIATIONS_PATH = '/bank/reconciliations'
export const reconciliationPath = (reconciliationId: string) =>
  `/bank/reconciliations/${encodeURIComponent(reconciliationId)}`
