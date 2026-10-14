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

/** Error codes the pages react to (the server translates every `QB_*` message itself). */
export const CODE = {
  userBanned: 'QB_USER_BANNED',
  emailNotVerified: 'EMAIL_NOT_VERIFIED',
} as const
