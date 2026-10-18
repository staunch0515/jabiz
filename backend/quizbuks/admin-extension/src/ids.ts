/**
 * The names the QuizBuks admin extension calls on the server (backend/quizbuks; docs/quizbuks/plans/Q9a-admin-extension.md).
 * QuizBuks has no endpoints of its own: the pages read the administrators' datasets and SQL templates, and the decision
 * of a review is the platform's `ApprovalPanel` (`APPROVAL_DECIDE`).
 */

/** The administrators' (default) datasets, read only. */
export const DATASET = {
  publication: 'urn:jabiz:dataset:default:QbPublication',
  tier: 'urn:jabiz:dataset:default:QbRewardTier',
  /** A version holds the correct answers: only `qb.admin.quiz.read` (the content administrators) reads it. */
  version: 'urn:jabiz:dataset:default:QbQuizVersion',
  sponsor: 'urn:jabiz:dataset:default:QbSponsor',
  ban: 'urn:jabiz:dataset:default:QbUserBan',
  /** The drafts of the legal texts, edited on the platform's generic pages (`qb.admin.content.write`; Q7b). */
  legalDoc: 'urn:jabiz:dataset:default:QbLegalDoc',
  /** The named users of an announcement (`qb.admin.content.write`; Q7c). */
  broadcastTarget: 'urn:jabiz:dataset:default:QbBroadcastTarget',
} as const

/** SQL templates (`queries/qb/admin`). */
export const TEMPLATE = {
  /** The review queue: no money (the content administrators read it). */
  publications: 'qb.admin.publications',
  /** The publications of a quiz an administrator took down, with the reasons (Q10b, review M-d). */
  quizTakedowns: 'qb.admin.quiz-takedowns',
  /** A publication's sponsor's funds; `qb.admin.finance.read`. */
  publicationFunds: 'qb.admin.publication-funds',
  sponsors: 'qb.admin.sponsors',
  users: 'qb.admin.users',
  /** Attempts without their amounts. */
  attempts: 'qb.admin.attempts',
  /** The four below need `qb.admin.finance.read`. */
  attemptRewards: 'qb.admin.attempt-rewards',
  transfers: 'qb.admin.transfers',
  topUps: 'qb.admin.topups',
  payoutAccounts: 'qb.admin.payout-accounts',
  /** Finance (Q9c): the overview's numbers, the wallets, the reconciliation and the two monthly reports (F-08). */
  financeSummary: 'qb.admin.finance-summary',
  /** The latest reconciliation and the open money tasks (`qb.reconcile` and `task.read`). */
  reconSummary: 'qb.admin.recon-summary',
  wallets: 'qb.admin.wallets',
  reconRuns: 'qb.admin.recon-runs',
  reconItems: 'qb.admin.recon-items',
  monthly: 'qb.admin.monthly',
  balances: 'qb.admin.balances',
  /** The platform's account balances (`ledger.read`). */
  accountBalances: 'jabiz.ledger.account_balances',
  /** The journal exports with their files and confirmations (Q9d; `qb.admin.finance.read`). */
  journalExports: 'qb.admin.journal-exports',
  /** The detail files of journal exports, one per part (Q9d review H1; `qb.admin.finance.read`). */
  journalExportParts: 'qb.admin.journal-export-parts',
  /** The account mapping now in effect (Q9d; `qb.admin.finance.read`). */
  journalMapping: 'qb.admin.journal-mapping',
  /** A user's acceptances of the terms and the privacy policy (Q7b; `qb.admin.users.read`). */
  consents: 'qb.admin.consents',
  /** The published versions of the legal texts and their withdrawals (Q7b review M1; `qb.admin.content.write`). */
  legalVersions: 'qb.admin.legal-versions',
  /** Every announcement and sponsor's notice with its statistics (Q7c; `qb.admin.content.write`). */
  broadcasts: 'qb.admin.broadcasts',
} as const

/** The announcements' processes (`messaging.BroadcastProcesses`, Q7c); the server checks each. */
export const BROADCAST = {
  save: 'QB_ADMIN_BROADCAST_SAVE',
  /** Sends now or schedules (a second factor, as other administration). */
  send: 'QB_ADMIN_BROADCAST_SEND',
  cancel: 'QB_ADMIN_BROADCAST_CANCEL',
  /** Any announcement or sponsor's notice, with a reason and a second factor every time. */
  withdraw: 'QB_ADMIN_BROADCAST_WITHDRAW',
} as const

/** Processes the finance pages run (Q9c); the server checks each. */
export const PROCESS = {
  /** Lets transfers to a taker held after a rejected or reversed transfer go on (`qb.payout.review`). */
  holdRelease: 'QB_TRANSFER_HOLD_RELEASE',
  /** The platform's issue of a report: archived with its content hash (`report.issue`). */
  reportIssue: 'REPORT_ISSUE',
  /** The next journal export as it would be now; writes nothing (`qb.journal.export`; Q9d). */
  journalPreview: 'QB_JOURNAL_PREVIEW',
  /** Exports the next month as a summary voucher (`qb.journal.export`, with a second factor). */
  journalExport: 'QB_JOURNAL_EXPORT',
  /** Records the accounting system's voucher number of an export (`qb.journal.confirm`). */
  journalConfirm: 'QB_JOURNAL_EXPORT_CONFIRM',
  /** Publishes a legal text's draft as its next version (`qb.admin.content.write`, with a second factor; Q7b). */
  legalPublish: 'QB_LEGAL_PUBLISH',
  /** Withdraws a version announced for later, before it takes effect (the same; review M1). */
  legalWithdraw: 'QB_LEGAL_WITHDRAW',
} as const

/** The platform's controlled changes (four eyes, decision D40): the mapping changes only through them. */
export const CONTROL = {
  propose: 'CONTROL_CHANGE_PROPOSE',
  publish: 'CONTROL_CHANGE_PUBLISH',
  /** Their version in the process catalogue. */
  version: 1,
  changes: 'urn:jabiz:dataset:platform:SysControlChange',
} as const

/** The reports of the monthly page (F-08), in the order shown. */
export const REPORTS = [TEMPLATE.monthly, TEMPLATE.balances] as const

/** The reason a ban's pause records instead of the ban's own (Q10b, review L3): shown in the reader's language. */
export const ACCOUNT_SUSPENDED = 'QB_ACCOUNT_SUSPENDED'
export type ReportId = (typeof REPORTS)[number]

/** The ledger's accounts (backend `QbLedger`), in the order the overview shows them. */
export const ACCOUNTS = ['2110', '2120', '2130', '1110', '1120', '4110', '5110'] as const

/** A transfer still sending this long after it was created is in doubt (the backend's `STALE_AFTER`). */
export const STALE_AFTER_MS = 60 * 60 * 1000

/** What a reconciliation difference's reference names, by its check (backend `ReconChecks`). */
export function referenceKind(check: string): 'topUp' | 'transfer' | null {
  if (check.startsWith('TOPUP_')) return 'topUp'
  if (check.startsWith('TRANSFER_')) return 'transfer'
  return null
}

/** The transfer state a large transfer waits for its review in. */
export const TRANSFER_REVIEW = 'REVIEW'

/** Dictionaries, labelled by the server in the request's language. */
export const DICTIONARY = {
  region: 'urn:jabiz:dict:quizbuks:region',
  publicationStatus: 'urn:jabiz:dict:quizbuks:publication-status',
  sponsorReviewStatus: 'urn:jabiz:dict:quizbuks:sponsor-review-status',
  materialKind: 'urn:jabiz:dict:quizbuks:material-kind',
  attemptStatus: 'urn:jabiz:dict:quizbuks:attempt-status',
  rewardStatus: 'urn:jabiz:dict:quizbuks:reward-status',
  transferStatus: 'urn:jabiz:dict:quizbuks:transfer-status',
  topUpStatus: 'urn:jabiz:dict:quizbuks:topup-status',
  payoutAccountStatus: 'urn:jabiz:dict:quizbuks:payout-account-status',
  reconCheck: 'urn:jabiz:dict:quizbuks:recon-check',
  legalKind: 'urn:jabiz:dict:quizbuks:legal-kind',
  broadcastStatus: 'urn:jabiz:dict:quizbuks:broadcast-status',
  broadcastType: 'urn:jabiz:dict:quizbuks:broadcast-type',
  broadcastAudience: 'urn:jabiz:dict:quizbuks:broadcast-audience',
  broadcastTarget: 'urn:jabiz:dict:quizbuks:broadcast-target',
  consentSource: 'urn:jabiz:dict:quizbuks:consent-source',
} as const

/** Permissions (`QbPermissions`): they only decide what the pages offer; the server checks every call. */
export const PERMISSION = {
  reviewPublication: 'qb.review.publication',
  reviewSponsor: 'qb.review.sponsor',
  quizRead: 'qb.admin.quiz.read',
  usersRead: 'qb.admin.users.read',
  financeRead: 'qb.admin.finance.read',
  payoutReview: 'qb.payout.review',
  reconcile: 'qb.reconcile',
  ledgerRead: 'ledger.read',
  reportIssue: 'report.issue',
  reportArchiveRead: 'report.archive.read',
  taskRead: 'task.read',
  journalExport: 'qb.journal.export',
  journalConfirm: 'qb.journal.confirm',
  /** Downloading a generated file (the journal's CSV) also needs the permissions it was kept with. */
  generatedFileRead: 'file.generated.read',
  controlPropose: 'control.propose',
  controlPublish: 'control.publish',
  /** Taking publications down (Q10b). */
  contentWrite: 'qb.admin.content.write',
} as const

/** The administrators' processes on a publication (Q10b, review M2), run from the platform's process page. */
export const PUBLICATION_ADMIN = {
  pause: 'QB_PUBLICATION_ADMIN_PAUSE',
  resume: 'QB_PUBLICATION_ADMIN_RESUME',
  end: 'QB_PUBLICATION_ADMIN_END',
  version: 1,
} as const

/** The widths the policy `qb.content.image` keeps (a smaller image is answered with the original). */
export const CONTENT_IMAGE_VARIANTS = ['w320', 'w640', 'w1280']
/** The widths the policy `qb.sponsor-avatar` keeps. */
export const AVATAR_VARIANTS = ['w128', 'w256']

/** The most rows one request asks for (the platform's page limit). */
export const PAGE_LIMIT = 100

/** A user id as the platform makes them (a UUID); anything else in an address is no user. */
export const isUserId = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)

/** The ledger's currency: the administration shows the real one (backend/quizbuks/CLAUDE.md section 4). */
export const CURRENCY = 'JPY'

/** The publication state a review waits in, and the onboarding state. */
export const PENDING_REVIEW = 'PENDING_REVIEW'
export const SPONSOR_PENDING = 'PENDING'

/** The extension's own pages (not platform paths, `PLATFORM_PATHS`). */
export const PATH = {
  reviews: '/qb/reviews',
  publication: (publicationId: string) => `/qb/reviews/publications/${encodeURIComponent(publicationId)}`,
  sponsor: (userId: string) => `/qb/reviews/sponsors/${encodeURIComponent(userId)}`,
  users: '/qb/users',
  user: (userId: string) => `/qb/users/${encodeURIComponent(userId)}`,
  finance: '/qb/finance',
  transfers: '/qb/finance/transfers',
  transfersWhere: (filter: Record<string, string>) => `/qb/finance/transfers?${new URLSearchParams(filter)}`,
  transferReview: (transferId: string) => `/qb/finance/transfers/${encodeURIComponent(transferId)}/review`,
  topUps: '/qb/finance/topups',
  topUpsWhere: (filter: Record<string, string>) => `/qb/finance/topups?${new URLSearchParams(filter)}`,
  reconciliation: '/qb/finance/reconciliation',
  reconRun: (runId: string) => `/qb/finance/reconciliation?${new URLSearchParams({ run: runId })}`,
  reports: '/qb/finance/reports',
  reportsOf: (month: string) => `/qb/finance/reports?${new URLSearchParams({ month })}`,
  journal: '/qb/finance/journal',
  journalMapping: '/qb/finance/journal/mapping',
  /** The legal texts: publishing the drafts and withdrawing announced versions (Q7b). */
  legal: '/qb/legal',
  /** Announcements and notices: writing, sending and withdrawing them, with their statistics (Q7c). */
  broadcasts: '/qb/broadcasts',
  /** The platform's own pages. */
  tasks: '/tasks',
} as const
