/**
 * The names the sponsor console's publishing pages call on the server (backend/quizbuks, package `publishing`;
 * docs/quizbuks/plans/Q4-publishing.md). Only the sponsor console publishes, so they live here rather than in the
 * shared `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */

/** Processes (`publishing.PublicationProcesses`, `PublicationLifecycle`). */
export const PUBLISHING_PROCESS = {
  save: 'QB_PUBLICATION_SAVE',
  /** The invitation list of an invited publication (Q25). */
  invitationAdd: 'QB_INVITATION_ADD',
  invitationRemove: 'QB_INVITATION_REMOVE',
  invitationSend: 'QB_INVITATION_SEND',
  /** The sponsor's own mail server, which the invitations leave through (Q29). */
  mailServerSet: 'QB_MAIL_SERVER_SET',
  mailServerRemove: 'QB_MAIL_SERVER_REMOVE',
  /** The sponsor says they handed the taker a cash reward (Q22). */
  cashMarkPaid: 'QB_CASH_REWARD_MARK_PAID',
  /** The pool of one-time codes of a digital prize (Q33): loaded a text at a time, the unused ones taken back. */
  digitalCodesLoad: 'QB_DIGITAL_CODES_LOAD',
  digitalCodesRevoke: 'QB_DIGITAL_CODES_REVOKE',
  /** The codes takers scan to collect a prize, and the sponsor's answer to a claim from the printed one (Q22b). */
  redeemIssue: 'QB_REDEEM_CODE_ISSUE',
  redemptionConfirm: 'QB_REDEMPTION_CONFIRM',
  redemptionDecline: 'QB_REDEMPTION_DECLINE',
  /** The sponsor's staff, who hand out prizes at the counter in the app (Q39): looked up by ID, named, removed. */
  staffFind: 'QB_SPONSOR_STAFF_FIND',
  staffAdd: 'QB_SPONSOR_STAFF_ADD',
  staffRemove: 'QB_SPONSOR_STAFF_REMOVE',
  delete: 'QB_PUBLICATION_DELETE',
  copy: 'QB_PUBLICATION_COPY',
  submit: 'QB_PUBLICATION_SUBMIT',
  withdraw: 'QB_PUBLICATION_WITHDRAW',
  pause: 'QB_PUBLICATION_PAUSE',
  resume: 'QB_PUBLICATION_RESUME',
  /** A publication that went on switches to another version of its quiz, reviewed again (Q51). */
  switchVersion: 'QB_PUBLICATION_SWITCH_VERSION',
  switchWithdraw: 'QB_PUBLICATION_SWITCH_WITHDRAW',
} as const

/** SQL templates (`queries/qb/sponsor`). The wizard's quizzes and versions are the content workshop's templates. */
export const PUBLISHING_TEMPLATE = {
  publications: 'qb.sponsor.publications',
  /** The cash rewards the sponsor owes or paid (Q22). */
  cashRewards: 'qb.sponsor.cash-rewards',
  /** The prizes takers collect at the sponsor's place (Q22b). */
  redemptions: 'qb.sponsor.redemptions',
  /** Q74: who handed over the sponsor's prizes, with their figures. */
  redemptionHandlers: 'qb.sponsor.redemption-handlers',
  /** The sponsor's staff (Q39). */
  staff: 'qb.sponsor.staff',
  /** The digital prizes won from the sponsor, and a publication's code pool: its counts and its codes' hints (Q33). */
  digitalPrizes: 'qb.sponsor.digital-prizes',
  digitalPoolSummary: 'qb.sponsor.digital-pool-summary',
  digitalCodes: 'qb.sponsor.digital-codes',
  /** Q70: the sponsor's referrals, per publication, without names. */
  referrals: 'qb.sponsor.referrals',
  /** An invited publication's list and its counts (Q25). */
  invitations: 'qb.sponsor.invitations',
  invitationSummary: 'qb.sponsor.invitation-summary',
  /** The methods the sponsor may publish with now and the highest reward per attempt (Q28). */
  rewardMethods: 'qb.sponsor.reward-methods',
  /** The sponsor's own mail server as the console shows it, never its password (Q29). */
  mailServer: 'qb.sponsor.mail-server',
} as const

/** The sponsor's own rows of the publishing entities (read only; writing is the processes'). */
export const PUBLISHING_DATASET = {
  publication: 'urn:jabiz:dataset:sponsor:QbPublication',
  tier: 'urn:jabiz:dataset:sponsor:QbRewardTier',
  /** The reward methods an administrator allowed the sponsor (Q22); no row means the parameter's default (Q28). */
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
  digitalDelivery: 'urn:jabiz:dict:quizbuks:digital-delivery',
  digitalStatus: 'urn:jabiz:dict:quizbuks:digital-prize-status',
  digitalCodeStatus: 'urn:jabiz:dict:quizbuks:digital-code-status',
  audience: 'urn:jabiz:dict:quizbuks:publication-audience',
  invitationStatus: 'urn:jabiz:dict:quizbuks:invitation-status',
} as const

/** Error codes the pages react to (`publishing.PublishingCodes`). */
export const PUBLISHING_CODE = {
  /** The publication changed since the page read it (another window): reload before editing on. */
  changed: 'QB_PUBLICATION_CHANGED',
  /** It is no draft any more (submitted elsewhere): reload; it can no longer be changed. */
  notDraft: 'QB_PUBLICATION_NOT_DRAFT',
} as const
