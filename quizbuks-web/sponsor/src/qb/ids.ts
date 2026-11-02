/**
 * The names the QuizBuks front ends call on the server (backend/quizbuks): processes, SQL templates, datasets,
 * dictionaries and file policies. QuizBuks has no endpoints of its own: everything goes through the platform's
 * generic ones (`/api/processes`, `/api/queries`, `/api/datasets`, `/api/dictionaries`, `/api/files`).
 *
 * This folder (`src/qb`) is the same in both SPAs (`quizbuks-web/app`, `quizbuks-web/sponsor`); CI compares them.
 */

/** Processes (`identity.ProfileProcesses`, `identity.SponsorProcesses`, `settings.SettingsProcesses`). */
export const PROCESS = {
  profileSave: 'QB_PROFILE_SAVE',
  settingsSave: 'QB_SETTINGS_SAVE',
  sponsorApply: 'QB_SPONSOR_APPLY',
  sponsorProfileSave: 'QB_SPONSOR_PROFILE_SAVE',
} as const

/** SQL templates. */
export const TEMPLATE = {
  mySignIns: 'qb.me.sign-ins',
} as const

/** Datasets (`identity.QbAccounts`, `country.QbCountries`, `settings.QbSettings`). */
export const DATASET = {
  myProfile: 'urn:jabiz:dataset:me:QbProfile',
  mySettings: 'urn:jabiz:dataset:me:QbUserSettings',
  mySponsor: 'urn:jabiz:dataset:sponsor:QbSponsor',
  countries: 'urn:jabiz:dataset:default:QbCountry',
} as const

/** Dictionaries. */
export const DICTIONARY = {
  currency: 'urn:jabiz:dict:quizbuks:currency',
  sponsorReviewStatus: 'urn:jabiz:dict:quizbuks:sponsor-review-status',
  loginOutcome: 'urn:jabiz:dict:platform:login-outcome',
} as const

/** File policies. */
export const FILE_POLICY = {
  sponsorAvatar: 'qb.sponsor-avatar',
} as const

/**
 * Answering (`play.AttemptProcesses`, `play.AttemptFinishing`, `play.PlayConfig`; templates `queries/qb/app`): what
 * the app's feed, detail, play, result and history pages call (docs/quizbuks/plans/QP-pages.md, QP2).
 */
export const PLAY = {
  view: 'QB_VIEW',
  start: 'QB_ATTEMPT_START',
  save: 'QB_ATTEMPT_SAVE',
  submit: 'QB_ATTEMPT_SUBMIT',
  /** A finished attempt's questions as shown and its answers, read any time later (Q20). */
  result: 'QB_ATTEMPT_RESULT',
  /** Q22: the taker confirms or disputes a cash reward; their open ones. */
  cashConfirm: 'QB_CASH_REWARD_CONFIRM',
  cashDispute: 'QB_CASH_REWARD_DISPUTE',
  cashRewards: 'qb.app.cash-rewards',
  /** Q22b: what a scanned redeem code stands for, the collection of a prize by it, and the taker's own prizes. */
  redeemCheck: 'QB_REDEEM_TOKEN_CHECK',
  redeemClaim: 'QB_REDEMPTION_CLAIM',
  redemptions: 'qb.app.redemptions',
  /** Q33: the winner opens a digital prize's content (recorded), says it works, or reports a problem with it. */
  digitalOpen: 'QB_DIGITAL_PRIZE_OPEN',
  digitalConfirm: 'QB_DIGITAL_PRIZE_CONFIRM',
  digitalDispute: 'QB_DIGITAL_PRIZE_DISPUTE',
  feed: 'qb.app.feed',
  publication: 'qb.app.publication',
  publicationTiers: 'qb.app.publication-tiers',
  history: 'qb.app.history',
} as const

/**
 * Customer matching (`audience.ProfileAttributeProcesses`, `publishing.PublicationAudience`, `audience.QbAudience`;
 * docs/quizbuks/plans/Q47-audience.md): the taker's own answers, a draft's conditions and their rounded count.
 */
export const AUDIENCE = {
  attributesSave: 'QB_PROFILE_ATTRIBUTES_SAVE',
  myAttributes: 'urn:jabiz:dataset:me:QbProfileAttribute',
  conditionsSave: 'QB_PUBLICATION_AUDIENCE_SAVE',
  count: 'QB_AUDIENCE_COUNT',
  sponsorConditions: 'urn:jabiz:dataset:sponsor:QbPublicationCriterion',
  /** 422: the caller's profile does not meet the publication's conditions. */
  notMatched: 'QB_AUDIENCE_NOT_MATCHED',
} as const

/**
 * Prerequisites (Q76, `publishing.PublicationPrerequisites`, `play.PrerequisiteCheck`;
 * docs/quizbuks/plans/Q76-prerequisites.md): the sponsor's other publications a taker must first pass or answer.
 */
export const PREREQUISITES = {
  /** Replaces a draft's prerequisites (at most `MAX`), each a required publication and `PASSED` or `ANSWERED`. */
  save: 'QB_PUBLICATION_PREREQUISITES_SAVE',
  /** The sponsor's own rows, with the required publications' names (`queries/qb/sponsor`). */
  sponsorTemplate: 'qb.sponsor.publication-prerequisites',
  /** 422: the caller has not yet passed (or answered) a publication this one requires. */
  notMet: 'QB_PREREQUISITE_NOT_MET',
  max: 5,
} as const

/** What a prerequisite asks of the taker on the required publication. */
export const REQUIREMENTS = ['PASSED', 'ANSWERED'] as const
export type Requirement = (typeof REQUIREMENTS)[number]

/** The widths the policy `qb.content.image` keeps of every image (covers, questions, options, materials). */
export const CONTENT_IMAGE_VARIANTS = ['w320', 'w640', 'w1280'] as const

/**
 * Terms, privacy and help (`messaging.LegalProcesses`, `messaging.QbLegal`; templates `queries/qb/public`,
 * `queries/qb/me`; docs/quizbuks/plans/Q7-messages-legal.md, Q7b).
 */
export const LEGAL = {
  accept: 'QB_CONSENT_ACCEPT',
  /** Anonymous (`GET /api/public/queries/{id}`). */
  publicLegal: 'qb.public.legal',
  publicLegalVersion: 'qb.public.legal-version',
  publicLegalHistory: 'qb.public.legal-history',
  publicFaq: 'qb.public.faq',
  myConsents: 'qb.me.consents',
  mySupport: 'qb.me.support',
  /** What each front end asks its users to accept. */
  appKinds: ['TERMS_APP', 'PRIVACY'],
  sponsorKinds: ['TERMS_SPONSOR', 'PRIVACY'],
} as const

/** Error codes the pages react to (the server translates every `QB_*` message itself). */
export const CODE = {
  userBanned: 'QB_USER_BANNED',
  emailNotVerified: 'EMAIL_NOT_VERIFIED',
  attemptAlreadyTaken: 'QB_ATTEMPT_ALREADY_TAKEN',
  attemptExpired: 'QB_ATTEMPT_EXPIRED',
  attemptNotInProgress: 'QB_ATTEMPT_NOT_IN_PROGRESS',
  attemptFinished: 'QB_ATTEMPT_FINISHED',
  /** 422: the terms in effect are to be accepted first (the consent prompt shows them). */
  consentRequired: 'QB_CONSENT_REQUIRED',
  /** 422: the page showed versions no longer in effect (reload them). */
  termsOutdated: 'QB_TERMS_OUTDATED',
  /** 503: the queue before a hot quiz's lock is full or its wait ran out (platform decision D45); try again later. */
  processBusy: 'PROCESS_BUSY',
} as const
