/**
 * The names the QuizBuks front ends call on the server (backend/quizbuks): processes, SQL templates, datasets,
 * dictionaries and file policies. QuizBuks has no endpoints of its own: everything goes through the platform's
 * generic ones (`/api/processes`, `/api/queries`, `/api/datasets`, `/api/dictionaries`, `/api/files`).
 *
 * This folder (`src/qb`) is the same in both SPAs (`quizbuks-web/app`, `quizbuks-web/sponsor`); CI compares them.
 */

/** Processes (`identity.ProfileProcesses`, `identity.SponsorProcesses`). */
export const PROCESS = {
  profileSave: 'QB_PROFILE_SAVE',
  sponsorApply: 'QB_SPONSOR_APPLY',
  sponsorProfileSave: 'QB_SPONSOR_PROFILE_SAVE',
} as const

/** SQL templates. */
export const TEMPLATE = {
  mySignIns: 'qb.me.sign-ins',
} as const

/** Datasets (`identity.QbAccounts`, `country.QbCountries`). */
export const DATASET = {
  myProfile: 'urn:jabiz:dataset:me:QbProfile',
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
  feed: 'qb.app.feed',
  publication: 'qb.app.publication',
  publicationTiers: 'qb.app.publication-tiers',
  history: 'qb.app.history',
  /** The taker's own attempts, read only; a finished one's `result` holds every question's right answer. */
  myAttempts: 'urn:jabiz:dataset:taker:QbAttempt',
} as const

/** The widths the policy `qb.content.image` keeps of every image (covers, questions, options, materials). */
export const CONTENT_IMAGE_VARIANTS = ['w320', 'w640', 'w1280'] as const

/** Error codes the pages react to (the server translates every `QB_*` message itself). */
export const CODE = {
  userBanned: 'QB_USER_BANNED',
  emailNotVerified: 'EMAIL_NOT_VERIFIED',
  attemptAlreadyTaken: 'QB_ATTEMPT_ALREADY_TAKEN',
  attemptExpired: 'QB_ATTEMPT_EXPIRED',
  attemptNotInProgress: 'QB_ATTEMPT_NOT_IN_PROGRESS',
  attemptFinished: 'QB_ATTEMPT_FINISHED',
  /** 503: the queue before a hot quiz's lock is full or its wait ran out (platform decision D45); try again later. */
  processBusy: 'PROCESS_BUSY',
} as const
