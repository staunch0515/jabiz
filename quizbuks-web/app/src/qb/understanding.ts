import { runQuery } from '@jabiz/client'
import { PLAY, type CertificationKey } from './ids'

/*
 * Q86 (docs/quizbuks/plans/Q86-agreement-quiz-tree.md): the understanding certifications as `qb.app.understanding`
 * gives them to the signed-in caller: whether the cards are offered at all (a controlled parameter), which the caller
 * holds and until when, and the publication of each one's current round. Read by the app's home and account, and by
 * the console's overview; both SPAs hold this file (src/qb).
 */

export interface Understanding {
  certification: CertificationKey
  enabled: boolean
  heldUntil: string | null
  roundPublicationId: string | null
  /** D-Q86-4: the handle of the platform's tree (the headquarters' official account's page); empty when none. */
  platformHandle: string | null
}

export const UNDERSTANDING_QUERY = ['qb', 'understanding'] as const

export async function readUnderstanding(at: string): Promise<Understanding[]> {
  const page = await runQuery<Record<string, unknown>>(PLAY.understanding, { params: { at }, limit: 10 })
  return page.items.flatMap((row): Understanding[] => typeof row.certification !== 'string' ? [] : [{
    certification: row.certification as CertificationKey,
    enabled: row.enabled !== false && row.enabled !== 'false',
    heldUntil: typeof row.heldUntil === 'string' && row.heldUntil ? row.heldUntil : null,
    roundPublicationId: typeof row.roundPublicationId === 'string' && row.roundPublicationId ? row.roundPublicationId : null,
    platformHandle: typeof row.platformHandle === 'string' && row.platformHandle ? row.platformHandle : null,
  }])
}
