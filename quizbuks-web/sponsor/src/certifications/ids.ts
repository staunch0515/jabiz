/**
 * The names the sponsor console calls on the server for the sponsor's own certifications (Q88a, backend
 * `certification.SponsorCertificationProcesses`, `publishing.PublicationRounds`;
 * docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md). Only the console defines them, so they live here
 * rather than in the shared `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */
export const CERTIFICATION_PROCESS = {
  create: 'QB_SPONSOR_CERTIFICATION_CREATE',
  update: 'QB_SPONSOR_CERTIFICATION_UPDATE',
  retire: 'QB_SPONSOR_CERTIFICATION_RETIRE',
  /** A draft as a round of one of the sponsor's certifications (or no round); a submitted one's round closed. */
  roundSet: 'QB_SPONSOR_ROUND_SET',
  roundClose: 'QB_SPONSOR_ROUND_CLOSE',
} as const

export const CERTIFICATION_TEMPLATE = {
  /** The sponsor's own certifications with their rounds and holders. */
  mine: 'qb.sponsor.certifications',
  /** The certification a publication of the sponsor's is a round of, if any. */
  round: 'qb.sponsor.publication-round',
} as const

/** `SponsorCertifications`: what a certification may be; the server checks the same. */
export const CERTIFICATION_LIMITS = {
  nameLength: 100,
  descriptionLength: 500,
  minValidityDays: 30,
  maxValidityDays: 3650,
  defaultValidityDays: 365,
  max: 20,
} as const

/** 422: the switch `qb.certification.sponsor-rounds.enabled` is off. */
export const CERTIFICATIONS_OFF = 'QB_SPONSOR_CERTIFICATIONS_OFF'
