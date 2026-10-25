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
  /** A quiz's draft as it is now, part by part (`qb.admin.quiz.read`; Q17). */
  quiz: 'urn:jabiz:dataset:default:QbQuiz',
  material: 'urn:jabiz:dataset:default:QbMaterial',
  materialImage: 'urn:jabiz:dataset:default:QbMaterialImage',
  question: 'urn:jabiz:dataset:default:QbQuestion',
  option: 'urn:jabiz:dataset:default:QbOption',
  sponsor: 'urn:jabiz:dataset:default:QbSponsor',
  ban: 'urn:jabiz:dataset:default:QbUserBan',
  /** The reward methods an administrator allowed a sponsor (Q22). */
  sponsorMethods: 'urn:jabiz:dataset:default:QbSponsorRewardMethods',
  /** The drafts of the legal texts, edited on the platform's generic pages (`qb.admin.content.write`; Q7b). */
  legalDoc: 'urn:jabiz:dataset:default:QbLegalDoc',
  /** The named users of an announcement (`qb.admin.content.write`; Q7c). */
  broadcastTarget: 'urn:jabiz:dataset:default:QbBroadcastTarget',
  /** Transfers: a bank withdrawal's account number is revealed from here (`qb.payout.bank.reveal`; Q13b). */
  transfer: 'urn:jabiz:dataset:default:QbTransfer',
} as const

/** SQL templates (`queries/qb/admin`). */
export const TEMPLATE = {
  /** The reward parameters in force (Q28): the default methods and the highest reward per attempt. */
  rewardDefaults: 'qb.admin.reward-defaults',
  /** The review queue: no money (the content administrators read it). */
  publications: 'qb.admin.publications',
  /** How many addresses an invited publication invited, wrote to and answered (Q25); the addresses are the sponsor's alone. */
  invitationSummary: 'qb.admin.invitation-summary',
  /** Every cash reward (Q22): disputes and late sponsors (`qb.admin.publication.read`). */
  cashRewards: 'qb.admin.cash-rewards',
  /** Every prize to collect at a sponsor's place (Q22b). */
  redemptions: 'qb.admin.redemptions',
  /** Every digital prize (Q33): codes, vouchers and links, with the disputes to decide (`qb.admin.publication.read`). */
  digitalPrizes: 'qb.admin.digital-prizes',
  /** Every sponsor's quizzes and a quiz's versions, without the versions' content (Q17; `qb.admin.quiz.read`). */
  quizzes: 'qb.admin.quizzes',
  quizVersions: 'qb.admin.quiz-versions',
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
  /** Q14: each taker's attempts and Kudos (finance). */
  takerKudos: 'qb.admin.taker-kudos',
  transfers: 'qb.admin.transfers',
  topUps: 'qb.admin.topups',
  /** Bank top-ups (Q13a): what recording and approving one needs (`qb.topup.bank.approve`). */
  bankTopUps: 'qb.admin.bank-topups',
  /** The bank top-up's settings in force (Q18a; `qb.admin.finance.read`). */
  bankSettings: 'qb.admin.bank-settings',
  /** Manual credits (Q18b): what recording and approving one needs (`qb.topup.bank.approve`). */
  manualCredits: 'qb.admin.manual-credits',
  /** Bank withdrawals (Q13b; finance). */
  bankWithdrawals: 'qb.admin.bank-withdrawals',
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
  /** The investors' figures (Q27): a row per UTC month of a period, and the merchants behind them (finance). */
  investorMetrics: 'qb.admin.investor-metrics',
  investorSponsors: 'qb.admin.investor-sponsors',
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
  /** AI generation (Q8a; `qb.admin.ai.read`): usage by sponsor and month, the jobs, the parameters in force. */
  aiUsage: 'qb.admin.ai-usage',
  aiJobs: 'qb.admin.ai-jobs',
  aiSettings: 'qb.admin.ai-settings',
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
  /** Q14: what the daily run would do for a taker now (`qb.admin.finance.read`), and doing it at once (`qb.transfer.now`). */
  transferPreview: 'QB_TRANSFER_PREVIEW',
  transferNow: 'QB_TRANSFER_NOW',
  /** Records the money of a bank top-up found on the statement, for another administrator's approval (Q13a). */
  bankRecord: 'QB_TOPUP_BANK_RECORD',
  /** Records a manual credit of a sponsor's wallet, for another administrator's approval (Q18b). */
  manualRecord: 'QB_TOPUP_MANUAL_RECORD',
  /** Proposes the bank top-up's settings in one step (Q18a; `control.propose`); another administrator publishes. */
  bankSettingsPropose: 'QB_BANK_SETTINGS_PROPOSE',
  /** Publishes another administrator's proposals of them together (Q18a; `control.publish`, a second factor). */
  bankSettingsPublish: 'QB_BANK_SETTINGS_PUBLISH',
  /** An approved bank withdrawal paid in the bank, or returned by it (Q13b; `qb.withdrawal.pay`). */
  withdrawalPaid: 'QB_WITHDRAWAL_PAID',
  withdrawalReturned: 'QB_WITHDRAWAL_RETURNED',
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
  /** Sets the reward methods a sponsor may publish with (Q22; `qb.admin.reward-methods`). */
  rewardMethodsSet: 'QB_SPONSOR_REWARD_METHODS_SET',
  /** Decides a disputed cash reward (Q22; `qb.cash.resolve`). */
  cashResolve: 'QB_CASH_REWARD_RESOLVE',
  /** An administrator voids a prize not yet collected (Q22b). */
  redemptionVoid: 'QB_REDEMPTION_VOID',
  /** Decides a disputed digital prize, or voids one still open (Q33; `qb.digital.resolve`). */
  digitalResolve: 'QB_DIGITAL_PRIZE_RESOLVE',
} as const

/** The platform's controlled changes (four eyes, decision D40): the mapping changes only through them. */
export const CONTROL = {
  propose: 'CONTROL_CHANGE_PROPOSE',
  publish: 'CONTROL_CHANGE_PUBLISH',
  /** Their version in the process catalogue. */
  version: 1,
  changes: 'urn:jabiz:dataset:platform:SysControlChange',
} as const

/** The reports that are issued and archived: the monthly page's (F-08), in the order shown, then the investors' (Q27). */
export const REPORTS = [TEMPLATE.monthly, TEMPLATE.balances, TEMPLATE.investorMetrics, TEMPLATE.investorSponsors] as const

/** The reason a ban's pause records instead of the ban's own (Q10b, review L3): shown in the reader's language. */
export const ACCOUNT_SUSPENDED = 'QB_ACCOUNT_SUSPENDED'
export type ReportId = (typeof REPORTS)[number]

/** The ledger's accounts (backend `QbLedger`), in the order the overview shows them. */
export const ACCOUNTS = ['2110', '2120', '2130', '1110', '1120', '4110', '5110', '5120'] as const

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
  audience: 'urn:jabiz:dict:quizbuks:publication-audience',
  publicationStatus: 'urn:jabiz:dict:quizbuks:publication-status',
  quizStatus: 'urn:jabiz:dict:quizbuks:quiz-status',
  quizKind: 'urn:jabiz:dict:quizbuks:quiz-kind',
  sponsorReviewStatus: 'urn:jabiz:dict:quizbuks:sponsor-review-status',
  materialKind: 'urn:jabiz:dict:quizbuks:material-kind',
  attemptStatus: 'urn:jabiz:dict:quizbuks:attempt-status',
  rewardStatus: 'urn:jabiz:dict:quizbuks:reward-status',
  rewardMethod: 'urn:jabiz:dict:quizbuks:reward-method',
  cashStatus: 'urn:jabiz:dict:quizbuks:cash-reward-status',
  cashResolution: 'urn:jabiz:dict:quizbuks:cash-reward-resolution',
  redemptionStatus: 'urn:jabiz:dict:quizbuks:redemption-status',
  digitalStatus: 'urn:jabiz:dict:quizbuks:digital-prize-status',
  digitalResolution: 'urn:jabiz:dict:quizbuks:digital-prize-resolution',
  digitalDelivery: 'urn:jabiz:dict:quizbuks:digital-delivery',
  topUpManualKind: 'urn:jabiz:dict:quizbuks:topup-manual-kind',
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
  /** Every publication (Q17): the content and the finance administrators. */
  publicationRead: 'qb.admin.publication.read',
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
  /** The AI page (Q8a). */
  aiRead: 'qb.admin.ai.read',
  /** A taker's transfer at once (Q14). */
  transferNow: 'qb.transfer.now',
  /** Bank top-ups (Q13a): finance records the money, another administrator approves it. */
  bankRecord: 'qb.topup.bank.record',
  bankApprove: 'qb.topup.bank.approve',
  /** Manual credits (Q18b): finance records them; the bank top-ups' approvers approve them. */
  manualRecord: 'qb.topup.manual.record',
  /** Q22: which reward methods a sponsor may use, and deciding disputed cash rewards. */
  rewardMethods: 'qb.admin.reward-methods',
  cashResolve: 'qb.cash.resolve',
  redemptionVoid: 'qb.redemption.void',
  /** Q33: deciding disputed digital prizes and voiding open ones. */
  digitalResolve: 'qb.digital.resolve',
  /** Bank withdrawals (Q13b): paying them, and the account number in plain text to do so. */
  withdrawalPay: 'qb.withdrawal.pay',
  bankReveal: 'qb.payout.bank.reveal',
} as const

/** The bank top-up states finance may record money against, and the one an approval waits in (Q13a). */
export const BANK_RECORDABLE = ['AWAITING', 'REPORTED', 'EXPIRED', 'CANCELLED'] as const
export const BANK_REVIEW = 'REVIEW'

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
  /** Every attempt (Q14), by user, publication and state. */
  attempts: '/qb/attempts',
  attemptsOf: (filter: Record<string, string>) => `/qb/attempts?${new URLSearchParams(filter)}`,
  user: (userId: string) => `/qb/users/${encodeURIComponent(userId)}`,
  /** Every sponsor's quizzes and one quiz's draft, versions and publications (Q17). */
  quizzes: '/qb/quizzes',
  quizzesWhere: (filter: Record<string, string>) => `/qb/quizzes?${new URLSearchParams(filter)}`,
  quiz: (quizId: string) => `/qb/quizzes/${encodeURIComponent(quizId)}`,
  /** Every publication in every state (Q17), by state, sponsor and quiz. */
  publications: '/qb/publications',
  /** Every cash reward (Q22): disputes to decide, late sponsors. */
  cashRewards: '/qb/cash-rewards',
  redemptions: '/qb/redemptions',
  /** Every digital prize (Q33): disputes to decide, late sponsors. */
  digitalPrizes: '/qb/digital-prizes',
  publicationsWhere: (filter: Record<string, string>) => `/qb/publications?${new URLSearchParams(filter)}`,
  finance: '/qb/finance',
  transfers: '/qb/finance/transfers',
  transfersWhere: (filter: Record<string, string>) => `/qb/finance/transfers?${new URLSearchParams(filter)}`,
  transferReview: (transferId: string) => `/qb/finance/transfers/${encodeURIComponent(transferId)}/review`,
  topUps: '/qb/finance/topups',
  topUpsWhere: (filter: Record<string, string>) => `/qb/finance/topups?${new URLSearchParams(filter)}`,
  /** Bank top-ups: recording the money and approving it (Q13a). */
  bankTopUps: '/qb/finance/bank-topups',
  /** The bank top-up's receiving account and limits (Q18a). */
  bankSettings: '/qb/finance/bank-settings',
  bankTopUp: (topUpId: string) => `/qb/finance/bank-topups/${encodeURIComponent(topUpId)}`,
  /** Manual credits: recording and approving them (Q18b). */
  manualCredits: '/qb/finance/manual-credits',
  manualCredit: (topUpId: string) => `/qb/finance/manual-credits/${encodeURIComponent(topUpId)}`,
  /** Bank withdrawals: reviewing, paying and returning them (Q13b). */
  bankWithdrawals: '/qb/finance/bank-withdrawals',
  reconciliation: '/qb/finance/reconciliation',
  reconRun: (runId: string) => `/qb/finance/reconciliation?${new URLSearchParams({ run: runId })}`,
  reports: '/qb/finance/reports',
  reportsOf: (month: string) => `/qb/finance/reports?${new URLSearchParams({ month })}`,
  /** The investors' figures by month (Q27). */
  investorMetrics: '/qb/finance/investor-metrics',
  journal: '/qb/finance/journal',
  journalMapping: '/qb/finance/journal/mapping',
  /** The legal texts: publishing the drafts and withdrawing announced versions (Q7b). */
  legal: '/qb/legal',
  /** Announcements and notices: writing, sending and withdrawing them, with their statistics (Q7c). */
  broadcasts: '/qb/broadcasts',
  /** AI generation (Q8a): usage, failures, the switch and the limits. */
  ai: '/qb/ai',
  /** The platform's own pages. */
  tasks: '/tasks',
} as const
