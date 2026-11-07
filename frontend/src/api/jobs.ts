import type { components } from '@jabiz/client'

/** The scheduled jobs and their runs (docs/design/11-ledger-events-jobs.md section 4; decision D77). */
export type Job = components['schemas']['JobView']
export type JobRun = components['schemas']['JobRunView']
export type FailingItem = components['schemas']['FailingItemView']
export type JobRunAccepted = components['schemas']['JobRunAccepted']

/** Outcomes of a run that left something undone: a re-run of their scheduled time is worth offering first. */
export const UNDONE = new Set(['FAILED', 'PARTIAL'])
