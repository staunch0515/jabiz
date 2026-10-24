/**
 * The names the sponsor console's publishing pages call on the server (backend/quizbuks, package `publishing`;
 * docs/quizbuks/plans/Q4-publishing.md). Only the sponsor console publishes, so they live here rather than in the
 * shared `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */

/** Processes (`publishing.PublicationProcesses`, `PublicationLifecycle`). */
export const PUBLISHING_PROCESS = {
  save: 'QB_PUBLICATION_SAVE',
  /** The sponsor says they handed the taker a cash reward (Q22). */
  cashMarkPaid: 'QB_CASH_REWARD_MARK_PAID',
  /** The codes takers scan to collect a prize, and the sponsor's answer to a claim from the printed one (Q22b). */
  redeemIssue: 'QB_REDEEM_CODE_ISSUE',
  redemptionConfirm: 'QB_REDEMPTION_CONFIRM',
  redemptionDecline: 'QB_REDEMPTION_DECLINE',
  delete: 'QB_PUBLICATION_DELETE',
  copy: 'QB_PUBLICATION_COPY',
  submit: 'QB_PUBLICATION_SUBMIT',
  withdraw: 'QB_PUBLICATION_WITHDRAW',
  pause: 'QB_PUBLICATION_PAUSE',
  resume: 'QB_PUBLICATION_RESUME',
} as const

/** SQL templates (`queries/qb/sponsor`). The wizard's quizzes and versions are the content workshop's templates. */
export const PUBLISHING_TEMPLATE = {
  publications: 'qb.sponsor.publications',
  /** The cash rewards the sponsor owes or paid (Q22). */
  cashRewards: 'qb.sponsor.cash-rewards',
  /** The prizes takers collect at the sponsor's place (Q22b). */
  redemptions: 'qb.sponsor.redemptions',
} as const

/** The sponsor's own rows of the publishing entities (read only; writing is the processes'). */
export const PUBLISHING_DATASET = {
  publication: 'urn:jabiz:dataset:sponsor:QbPublication',
  tier: 'urn:jabiz:dataset:sponsor:QbRewardTier',
  /** The reward methods an administrator allowed the sponsor (Q22); no row means the platform's Kudos only. */
  rewardMethods: 'urn:jabiz:dataset:sponsor:QbSponsorRewardMethods',
} as const

/** Dictionaries, labelled by the server in the request's language. */
export const PUBLISHING_DICTIONARY = {
  status: 'urn:jabiz:dict:quizbuks:publication-status',
  endReason: 'urn:jabiz:dict:quizbuks:publication-end-reason',
  region: 'urn:jabiz:dict:quizbuks:region',
  rewardMethod: 'urn:jabiz:dict:quizbuks:reward-method',
  cashStatus: 'urn:jabiz:dict:quizbuks:cash-reward-status',
  redemptionStatus: 'urn:jabiz:dict:quizbuks:redemption-status',
} as const

/** Error codes the pages react to (`publishing.PublishingCodes`). */
export const PUBLISHING_CODE = {
  /** The publication changed since the page read it (another window): reload before editing on. */
  changed: 'QB_PUBLICATION_CHANGED',
  /** It is no draft any more (submitted elsewhere): reload; it can no longer be changed. */
  notDraft: 'QB_PUBLICATION_NOT_DRAFT',
} as const
