import type { components } from '@jabiz/client'

/** The integrity seals (docs/design/21-audit-retention.md section 2). */
export type IntegrityHead = components['schemas']['IntegrityHead']
export type IntegrityCheckSummary = components['schemas']['IntegrityCheckSummary']
export type IntegrityCheckDetail = components['schemas']['IntegrityCheckDetail']
export type IntegrityProblem = components['schemas']['IntegrityProblem']

/** The output of INTEGRITY_VERIFY. */
export interface VerifyOutput {
  checkNo: number
  intact: boolean
  problemCount: number
  sealCount: number
  rowCount: number
  unsealedCount: number
}
