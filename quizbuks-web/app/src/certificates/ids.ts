/**
 * The names the app's certificate pages call on the server (Q77, docs/quizbuks/plans/Q77-certificates.md): the two
 * public templates anybody reads. The caller's own certificates and their processes are `qb/certificates.ts` (shared
 * with the Sponsor app).
 */

export const CERTIFICATE_TEMPLATE = {
  verify: 'qb.public.certificate',
  takerPage: 'qb.public.taker-page',
} as const
