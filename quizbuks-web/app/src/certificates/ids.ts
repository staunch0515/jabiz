/**
 * The names the app's certificates call on the server (Q77, docs/quizbuks/plans/Q77-certificates.md): the caller's
 * certifications and certificates, and the two public templates anybody reads.
 */

export const CERTIFICATE_PROCESS = {
  mine: 'QB_MY_CERTIFICATIONS',
  issue: 'QB_CERTIFICATE_ISSUE',
} as const

/** The certificate's document layout (its PDFs are documents of it whose subject is the certificate). */
export const CERTIFICATE_LAYOUT = 'qb.certificate'

export const CERTIFICATE_TEMPLATE = {
  verify: 'qb.public.certificate',
  takerPage: 'qb.public.taker-page',
} as const
