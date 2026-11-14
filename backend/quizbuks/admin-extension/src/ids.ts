/**
 * The names the QuizBuks admin extension calls on the server (backend/quizbuks; docs/quizbuks/plans/Q9a-admin-extension.md).
 * QuizBuks has no endpoints of its own: the pages read the administrators' datasets and SQL templates, and the decision
 * of a review is the platform's `ApprovalPanel` (`APPROVAL_DECIDE`).
 */

/** The administrators' (default) datasets, read only. */
export const DATASET = {
  publication: 'urn:jabiz:dataset:default:QbPublication',
  /** Q49a: takers' reports of publications (an operator's session: its own sponsors'). */
  report: 'urn:jabiz:dataset:default:QbReport',
  /** Q82: takers' conversations with sponsors and their messages (an operator's session: its own sponsors'). */
  conversation: 'urn:jabiz:dataset:default:QbConversation',
  conversationMessage: 'urn:jabiz:dataset:default:QbConversationMessage',
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
  /**
   * F-C1: a Sponsor's asset library and where each asset was copied to, read only (`qb.admin.publication.read`; an
   * operator's session: its own Sponsors').
   */
  asset: 'urn:jabiz:dataset:default:QbAsset',
  assetUse: 'urn:jabiz:dataset:default:QbAssetUse',
  /** F-C4: a Sponsor's imports of sponsor packages, read only (the same readers as the asset library). */
  packageImport: 'urn:jabiz:dataset:default:QbPackageImport',
} as const

/** SQL templates (`queries/qb/admin`). */
export const TEMPLATE = {
  /** F-S2b3a: the knowledge maps: every one (the platform's), the operator's own, a version node by node, the nodes lit. */
  trees: 'qb.admin.trees',
  operatorTrees: 'qb.operator.trees',
  treeVersion: 'qb.admin.tree-version',
  treeLit: 'qb.admin.tree-lit',
  operatorTreeVersion: 'qb.operator.tree-version',
  operatorTreeLit: 'qb.operator.tree-lit',
  /** F-S3e: an operator's copy against its template, and the templates an operator may copy. */
  operatorTreeTemplateDiff: 'qb.operator.tree-template-diff',
  operatorTreeTemplates: 'qb.operator.tree-templates',
  /** F-C2: the sponsors' usage by month and meter (headquarters every sponsor, an operator its own). */
  usage: 'qb.admin.usage',
  /** Q73e-1: a publication by the language its attempts are answered in. */
  publicationLanguages: 'qb.admin.publication-languages',
  /** Q73d-2: the latest AI comparison of a publication's translations in one language (its reviewers). */
  translationCheck: 'qb.admin.ai-translation-check',
  /** Q70: every referral, with its people (support and finance). */
  referrals: 'qb.admin.referrals',
  /** Sponsors' pages (Q60a-3): the list, the review queue and one page (an operator's own sponsors' only). */
  sponsorPages: 'qb.admin.sponsor-pages',
  /** Sponsors' products (Q60b-3), the list and one sponsor's (an operator's own sponsors' only). */
  sponsorProducts: 'qb.admin.sponsor-products',
  /** Q49a: the reported publications, with their reports counted by reason. */
  abuseReports: 'qb.admin.reports',
  /** F-S11: headquarters' exceptions to the rule on foreign videos, the starts it refused by day, and its sites. */
  regionExceptions: 'qb.admin.region-exceptions',
  regionRefusals: 'qb.admin.region-refusals',
  videoSites: 'qb.admin.video-sites',
  /** F-S9: the word list of topics with its figures by topic (headquarters). */
  topics: 'qb.admin.topics',
  /** F-S8: the figures of blocking by kind, and the Sponsors most blocked (rounded; headquarters). */
  blocks: 'qb.admin.blocks',
  blockedSponsors: 'qb.admin.blocked-sponsors',
  /** F-S13: the accounts the daily cap of exposures refused (headquarters and the operators, scoped). */
  exposureSuspicious: 'qb.admin.exposure-suspicious',
  /** F-S14: the ledger of shared videos' uses, read only (headquarters and the operators, scoped). */
  videoUses: 'qb.admin.video-uses',
  /** F-S14: the same as the providers' operators read it, and the history of a use through each path. */
  videoUsesProvided: 'qb.admin.video-uses-provided',
  videoUseHistory: 'qb.admin.video-use-history',
  videoUseHistoryProvided: 'qb.admin.video-use-history-provided',
  /** F-S12: the videos Sponsors share (headquarters every provider's, an operator its own Sponsors'). */
  sharedVideos: 'qb.admin.shared-videos',
  /** F-C3: the publications that went on with a shared video (an operator's own Sponsors' only). */
  sharedVideoUses: 'qb.admin.shared-video-uses',
  /** F-M3: the Sponsors' company verifications (headquarters every Sponsor's, an operator its own). */
  sponsorVerifications: 'qb.admin.sponsor-verifications',
  /** Q60c-2: one sponsor's page views and product clicks in a period. */
  sponsorStats: 'qb.admin.sponsor-stats',
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
  /** F-M14: for how many days in a row an item for attention has been found (`qb.reconcile`). */
  reconAttentionDays: 'qb.admin.recon-attention-days',
  monthly: 'qb.admin.monthly',
  balances: 'qb.admin.balances',
  /** Q87: the platform's fees charged to sponsors, their invoices, and the requests another administrator approves. */
  feeCharges: 'qb.admin.fee-charges',
  feeInvoices: 'qb.admin.fee-invoices',
  /** F-M1: one Sponsor's account that pays the platform's fees (finance; an operator's own Sponsors'), and in all. */
  feeAccount: 'qb.admin.fee-account',
  feeAccountLots: 'qb.admin.fee-account-lots',
  feePayments: 'qb.admin.fee-payments',
  feeAccountTotals: 'qb.admin.fee-account-totals',
  operatorFeeAccount: 'qb.operator.fee-account',
  operatorFeeAccountLots: 'qb.operator.fee-account-lots',
  operatorFeePayments: 'qb.operator.fee-payments',
  feeRequests: 'qb.admin.fee-requests',
  /** F-M7: the credit notes (finance; read only for an operator) and what a fee may still be credited. */
  feeCreditNotes: 'qb.admin.fee-credit-notes',
  operatorFeeCreditNotes: 'qb.operator.fee-credit-notes',
  feeChargeCredit: 'qb.admin.fee-charge-credit',
  /** F-M2: the platform fee Kudos granted (headquarters, read only), each and summed; an operator's Sponsor's onboarding. */
  feeCreditGrants: 'qb.admin.fee-credit-grants',
  feeCreditSummary: 'qb.admin.fee-credit-summary',
  operatorFeeCreditOnboarding: 'qb.operator.fee-credit-onboarding',
  /** The investors' figures (Q27): a row per UTC month of a period, and the sponsors behind them (finance). */
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
  /** Q77: the certificates people issued (support checks one a verifier asks about). */
  certificates: 'qb.admin.certificates',
  /** F-S5b: an operator's members' own lookup of the certificates of the maps their operator looks after. */
  operatorCertificates: 'qb.operator.certificates',
  /** The published versions of the legal texts and their withdrawals (Q7b review M1; `qb.admin.content.write`). */
  legalVersions: 'qb.admin.legal-versions',
  /** AI generation (Q8a; `qb.admin.ai.read`): usage by sponsor and month, the jobs, the parameters in force. */
  aiUsage: 'qb.admin.ai-usage',
  aiJobs: 'qb.admin.ai-jobs',
  aiSettings: 'qb.admin.ai-settings',
  /** Every announcement and sponsor's notice with its statistics (Q7c; `qb.admin.content.write`). */
  broadcasts: 'qb.admin.broadcasts',
  /** Q48a, headquarters: the operators, an operator's members, the reviews they decided (`qb.admin.spot-check`). */
  operators: 'qb.admin.operators',
  /** F-S1: what is wrong with the operators' structure, one row a problem (the daily check's rows). */
  operatorHealth: 'qb.admin.operator-health',
  /** F-S1b: each operator's reviews waiting and decided since a time. */
  operatorWorkload: 'qb.admin.operator-workload',
  operatorMembers: 'qb.admin.operator-members',
  operatorReviews: 'qb.admin.operator-reviews',
  /** Q48a, an operator's own pages (`qb.operator.use`): the totals and the sponsors, never a taker's profile. */
  operatorOverview: 'qb.operator.overview',
  operatorSponsors: 'qb.operator.sponsors',
  /** Q87b: how the operator's sponsors pay the platform's fees. */
  operatorBilling: 'qb.operator.sponsor-billing',
  /** Q48c, AI prospecting: an operator's own (`qb.prospect.use`) and headquarters' for any owner (`qb.admin.prospect`). */
  operatorProspects: 'qb.operator.prospects',
  operatorProspectSetting: 'qb.operator.prospect-setting',
  operatorProspectDirections: 'qb.operator.prospect-directions',
  operatorProspectUsage: 'qb.operator.prospect-usage',
  adminProspects: 'qb.admin.prospects',
  adminProspectSetting: 'qb.admin.prospect-setting',
  adminProspectDirections: 'qb.admin.prospect-directions',
  adminProspectUsage: 'qb.admin.prospect-usage',
  adminProspectBlocks: 'qb.admin.prospect-blocks',
  /** Q48d: the funnel by direction. */
  operatorProspectFunnel: 'qb.operator.prospect-funnel',
  adminProspectFunnel: 'qb.admin.prospect-funnel',
  /** F-F8 (T9): the platform switch of prospecting in force (`qb.admin.prospect`). */
  adminProspectSwitch: 'qb.admin.prospect-switch',
  /** F-F10b: the first launch's money switches in force, set against the registry (`qb.admin.finance.read`). */
  adminFirstLaunchSwitches: 'qb.admin.first-launch-switches',
  /** Q48b: the operators' monthly settlements, every operator's (finance) and the caller operator's own. */
  operatorSettlements: 'qb.admin.operator-settlements',
  ownSettlements: 'qb.operator.settlements',
  /** Q48e-2, the operator's own (`qb.operator.use`): its account (balance, bank account), withdrawals and plans. */
  operatorAccount: 'qb.operator.account',
  ownWithdrawals: 'qb.operator.withdrawals',
  /** F-M4: the caller operator's transfers to its direct Sponsor. */
  ownTransfers: 'qb.operator.transfers',
  ownPlans: 'qb.operator.plans',
  /** Q48e-2: every operator's withdrawals (finance) and plan changes (headquarters). */
  operatorWithdrawals: 'qb.admin.operator-withdrawals',
  operatorPlans: 'qb.admin.operator-plans',
} as const

/** The operators' settlements' processes (backend `operator.OperatorSettlements`, Q48b). */
export const SETTLEMENT_PROCESS = {
  pay: 'QB_OPERATOR_SETTLEMENT_PAY',
  statement: 'QB_OPERATOR_SETTLEMENT_STATEMENT',
} as const

/**
 * The operators' accounts, withdrawals and plans (backend `operator.OperatorWithdrawals` and `OperatorPlans`, Q48e-2):
 * the operator's lead sets the bank account and withdraws (both with a recent second factor, which the platform asks
 * for), finance records the payment or rejects it, headquarters proposes a plan; another administrator approves.
 */
export const ACCOUNT_PROCESS = {
  bankAccountSave: 'QB_OPERATOR_BANK_ACCOUNT_SAVE',
  withdrawalRequest: 'QB_OPERATOR_WITHDRAWAL_REQUEST',
  withdrawalCancel: 'QB_OPERATOR_WITHDRAWAL_CANCEL',
  /** F-M4: the lead moves money of the account to the operator's direct Sponsor (platform fees only). */
  transferToDirect: 'QB_OPERATOR_TRANSFER_TO_DIRECT',
  withdrawalPay: 'QB_OPERATOR_WITHDRAWAL_PAY',
  withdrawalReject: 'QB_OPERATOR_WITHDRAWAL_REJECT',
  planSet: 'QB_OPERATOR_PLAN_SET',
} as const

/** AI prospecting's processes (backend `prospect`, Q48c); the owner is the caller's operator, or headquarters. */
export const PROSPECT_PROCESS = {
  settingsSave: 'QB_PROSPECT_SETTINGS_SAVE',
  directionSave: 'QB_PROSPECT_DIRECTION_SAVE',
  approve: 'QB_PROSPECT_APPROVE',
  discard: 'QB_PROSPECT_DISCARD',
  blockAdd: 'QB_PROSPECT_BLOCK_ADD',
  mailServerSet: 'QB_PROSPECT_MAIL_SERVER_SET',
  mailServerRemove: 'QB_PROSPECT_MAIL_SERVER_REMOVE',
} as const

/** Operators' processes (backend `operator`, Q48a); the server checks each, and an operator's acts their membership. */
export const OPERATOR_PROCESS = {
  save: 'QB_OPERATOR_SAVE',
  memberAdd: 'QB_OPERATOR_MEMBER_ADD',
  memberRemove: 'QB_OPERATOR_MEMBER_REMOVE',
  assign: 'QB_SPONSOR_OPERATOR_ASSIGN',
  spotCheck: 'QB_REVIEW_SPOT_CHECK',
  /** The operator's own: its team, the lead's removal of a member, takedowns and bans. */
  team: 'QB_OPERATOR_TEAM',
  memberDrop: 'QB_OPERATOR_MEMBER_DROP',
  pause: 'QB_OPERATOR_PUBLICATION_PAUSE',
  resume: 'QB_OPERATOR_PUBLICATION_RESUME',
  end: 'QB_OPERATOR_PUBLICATION_END',
  ban: 'QB_OPERATOR_SPONSOR_BAN',
  unban: 'QB_OPERATOR_SPONSOR_UNBAN',
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
  /** F-S2b2: asks another member to approve revoking a lit node (`qb.tree.lit.revoke`, reason required). */
  treeLitRevoke: 'QB_TREE_LIT_REVOKE',
  /** F-S3e: editing a platform template (`qb.tree.platform.write`) or an operator's copy (`qb.tree.edit`). */
  treeCreate: 'QB_TREE_CREATE',
  treeCopy: 'QB_TREE_COPY',
  treeSave: 'QB_TREE_DRAFT_SAVE',
  treeSubmit: 'QB_TREE_SUBMIT',
  treeWithdraw: 'QB_TREE_WITHDRAW',
  treeAdopt: 'QB_TREE_ADOPT',
  /** F-S3a: one person's lighting of one map, recorded (`qb.admin.tree-state.read`). */
  supportTreeState: 'QB_SUPPORT_TREE_STATE',
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
  /** An administrator voids a prize not yet collected (Q22b). */
  redemptionVoid: 'QB_REDEMPTION_VOID',
  /** Decides a disputed digital prize, or voids one still open (Q33; `qb.digital.resolve`). */
  digitalResolve: 'QB_DIGITAL_PRIZE_RESOLVE',
} as const

/**
 * The platform's fees (backend `fee`, Q87): finance asks another administrator to approve a fee charged by hand or an
 * invoice voided; an operator (or finance) sets how a sponsor of theirs is billed, with a reason.
 */
export const FEE_PROCESS = {
  request: 'QB_FEE_REQUEST',
  billingSet: 'QB_OPERATOR_BILLING_SET',
  /** F-M2: a lead of the Sponsor's operator confirms the Sponsor, which grants the onboarding platform fee Kudos. */
  onboarding: 'QB_FEE_CREDIT_ONBOARDING',
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
export const ACCOUNTS = ['2110', '2120', '2130', '1110', '1120', '4110', '5110', '5120',
  // Q87: the sponsors' cash accounts, the fees receivable, the fee revenue and the tax on it.
  '2160', '1130', '4120', '2150',
  // Q88b: the entry fees unsettled (until the daily job moves them to the sponsors' cash accounts).
  '2170',
  // F-M5: the platform fees prepaid when a publication is submitted.
  '2180',
  // F-M1: the sponsors' granted credit (platform fee Kudos); F-M2: its promotion expenses.
  '2190', '5140'] as const

/** A transfer still sending this long after it was created is in doubt (the backend's `STALE_AFTER`). */
export const STALE_AFTER_MS = 60 * 60 * 1000

/** What a reconciliation difference's reference names, by its check (backend `ReconChecks`). */
export function referenceKind(check: string): 'topUp' | 'transfer' | null {
  if (check.startsWith('TOPUP_')) return 'topUp'
  if (check.startsWith('TRANSFER_')) return 'transfer'
  return null
}

/**
 * F-M14: how much a reconciliation check finding something matters, mirroring `ReconChecks.ATTENTION` and
 * `ReconChecks.NOTES` (a Vitest reads the Java and fails on a difference); every other check is a difference.
 */
export const ATTENTION_CHECKS: ReadonlySet<string> = new Set(['DISTRIBUTION_PRICE_RANGE', 'AUDIENCE_REFUND_NO_PERIOD',
  'CREDIT_UNUSED_WITH_DUE', 'HELP_USAGE_TOTAL', 'EXPOSURE_PRICE_RANGE'])
export const NOTE_CHECKS: ReadonlySet<string> = new Set(['TAKER_WALLET_LAG', 'DISTRIBUTION_AT_FLOOR',
  'EXPOSURE_UNPURCHASED', 'OPERATOR_GIFT_CARRY_OPEN', 'AI_RESERVATION_SHORT'])

export type ReconLevel = 'DIFFERENCE' | 'ATTENTION' | 'NOTE'

/** The level of a check (backend `ReconChecks.level`). */
export function reconLevel(check: string): ReconLevel {
  return NOTE_CHECKS.has(check) ? 'NOTE' : ATTENTION_CHECKS.has(check) ? 'ATTENTION' : 'DIFFERENCE'
}

/** The transfer state a large transfer waits for its review in. */
export const TRANSFER_REVIEW = 'REVIEW'

/** Dictionaries, labelled by the server in the request's language. */
export const DICTIONARY = {
  /** F-S2: knowledge maps: whose, who lights them, their state and their versions' state, who lit a node. */
  treeOwner: 'urn:jabiz:dict:quizbuks:tree-owner',
  treeSubject: 'urn:jabiz:dict:quizbuks:tree-subject',
  treeStatus: 'urn:jabiz:dict:quizbuks:tree-status',
  treeVersionStatus: 'urn:jabiz:dict:quizbuks:tree-version-status',
  treeLitSubject: 'urn:jabiz:dict:quizbuks:tree-lit-subject',
  /** F-C2: the usage meters (`usage.UsageMeters`). */
  usageMeter: 'urn:jabiz:dict:quizbuks:usage-meter',
  /** F-C1: an asset's kind, what a text asset is for, and the places an asset is copied to. */
  assetKind: 'urn:jabiz:dict:quizbuks:asset-kind',
  assetTextUsage: 'urn:jabiz:dict:quizbuks:asset-text-usage',
  assetUseTarget: 'urn:jabiz:dict:quizbuks:asset-use-target',
  /** F-C4: how a sponsor package was imported, and the import's state. */
  packageImportMode: 'urn:jabiz:dict:quizbuks:package-import-mode',
  packageImportStatus: 'urn:jabiz:dict:quizbuks:package-import-status',
  sponsorPageStatus: 'urn:jabiz:dict:quizbuks:sponsor-page-status',
  sponsorProductStatus: 'urn:jabiz:dict:quizbuks:sponsor-product-status',
  reportReason: 'urn:jabiz:dict:quizbuks:report-reason',
  reportStatus: 'urn:jabiz:dict:quizbuks:report-status',
  region: 'urn:jabiz:dict:quizbuks:region',
  contentLanguage: 'urn:jabiz:dict:quizbuks:content-language',
  audience: 'urn:jabiz:dict:quizbuks:publication-audience',
  publicationStatus: 'urn:jabiz:dict:quizbuks:publication-status',
  publicationEndReason: 'urn:jabiz:dict:quizbuks:publication-end-reason',
  quizStatus: 'urn:jabiz:dict:quizbuks:quiz-status',
  quizKind: 'urn:jabiz:dict:quizbuks:quiz-kind',
  sponsorReviewStatus: 'urn:jabiz:dict:quizbuks:sponsor-review-status',
  materialKind: 'urn:jabiz:dict:quizbuks:material-kind',
  attemptStatus: 'urn:jabiz:dict:quizbuks:attempt-status',
  rewardStatus: 'urn:jabiz:dict:quizbuks:reward-status',
  rewardMethod: 'urn:jabiz:dict:quizbuks:reward-method',
  cashStatus: 'urn:jabiz:dict:quizbuks:cash-reward-status',
  /** Q70: a referral's states. */
  referralStatus: 'urn:jabiz:dict:quizbuks:referral-status',
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
  /** F-M14: a check's level (difference, needs attention, note), keyed by the check. */
  reconLevel: 'urn:jabiz:dict:quizbuks:recon-level',
  legalKind: 'urn:jabiz:dict:quizbuks:legal-kind',
  broadcastStatus: 'urn:jabiz:dict:quizbuks:broadcast-status',
  broadcastType: 'urn:jabiz:dict:quizbuks:broadcast-type',
  broadcastAudience: 'urn:jabiz:dict:quizbuks:broadcast-audience',
  broadcastTarget: 'urn:jabiz:dict:quizbuks:broadcast-target',
  consentSource: 'urn:jabiz:dict:quizbuks:consent-source',
  operatorStatus: 'urn:jabiz:dict:quizbuks:operator-status',
  spotCheckResult: 'urn:jabiz:dict:quizbuks:spot-check-result',
  prospectStatus: 'urn:jabiz:dict:quizbuks:prospect-status',
  prospectBlockReason: 'urn:jabiz:dict:quizbuks:prospect-block-reason',
  settlementStatus: 'urn:jabiz:dict:quizbuks:operator-settlement-status',
  /** Q48e-2: the operators' withdrawals and plans, and the kind of a Japanese bank account. */
  operatorWithdrawalStatus: 'urn:jabiz:dict:quizbuks:operator-withdrawal-status',
  operatorPlan: 'urn:jabiz:dict:quizbuks:operator-plan',
  operatorPlanStatus: 'urn:jabiz:dict:quizbuks:operator-plan-status',
  bankAccountType: 'urn:jabiz:dict:quizbuks:bank-account-type',
  /** Q87: the platform's fees. */
  billingMode: 'urn:jabiz:dict:quizbuks:billing-mode',
  feeKind: 'urn:jabiz:dict:quizbuks:fee-kind',
  feeInvoiceStatus: 'urn:jabiz:dict:quizbuks:fee-invoice-status',
  /** F-M1: the account's sub-accounts and where its lots came from. */
  feeSubAccount: 'urn:jabiz:dict:quizbuks:fee-sub-account',
  feeCreditSource: 'urn:jabiz:dict:quizbuks:fee-credit-source',
  cashSource: 'urn:jabiz:dict:quizbuks:cash-source',
  feeRequestKind: 'urn:jabiz:dict:quizbuks:fee-request-kind',
  feeRequestStatus: 'urn:jabiz:dict:quizbuks:fee-request-status',
} as const

/** Permissions (`QbPermissions`): they only decide what the pages offer; the server checks every call. */
export const PERMISSION = {
  /** F-S2: every knowledge map (the platform's administrators), and revoking a lit node (operators and the platform). */
  treesRead: 'qb.admin.trees.read',
  treeLitRevoke: 'qb.tree.lit.revoke',
  /** F-S2a: deciding the review of a Sponsor's map or an operator's copy (operators), of a platform template. */
  reviewTree: 'qb.review.tree',
  treePlatformWrite: 'qb.tree.platform.write',
  /** F-S3e: editing a map one may edit (the server checks the owner), and support's lookup of one person. */
  treeEdit: 'qb.tree.edit',
  treeStateRead: 'qb.admin.tree-state.read',
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
  /** Reading the controlled changes (`SysControlChange`) and the approvals. */
  approvalRead: 'approval.read',
  /** Taking publications down (Q10b). */
  contentWrite: 'qb.admin.content.write',
  /** F-S11: the exceptions to the rule on foreign videos and mainland China (read, and add, change or revoke). */
  regionRead: 'qb.admin.region.read',
  regionWrite: 'qb.admin.region.write',
  /** F-S11: the refusal counts and the address database (headquarters only, not the operators). */
  regionMonitor: 'qb.admin.region.monitor',
  /** F-S12: the shared videos (read) and taking one down (write); reviewing them is `reviewPublication`'s. */
  sharedVideoRead: 'qb.admin.shared-video.read',
  sharedVideoWrite: 'qb.admin.shared-video.write',
  /** F-M3: the company verifications (read) and revoking one (always with a second factor). */
  verificationRead: 'qb.admin.verification.read',
  verificationRevoke: 'qb.sponsor-verification.revoke',
  /** F-S9: the word list of topics and its figures (read), adding, renaming, deactivating and merging (write). */
  topicRead: 'qb.admin.topic.read',
  topicWrite: 'qb.admin.topic.write',
  /** F-S8: the figures of blocking (aggregates only). */
  blockRead: 'qb.admin.block.read',
  /** F-S13: the suspicious inflation of exposures (headquarters and the operators). */
  exposureRead: 'qb.admin.exposure.read',
  /** F-S14: the ledger of shared videos' uses, read only (headquarters and the operators). */
  videoUseRead: 'qb.admin.video-use.read',
  /** The AI page (Q8a). */
  aiRead: 'qb.admin.ai.read',
  /** A taker's transfer at once (Q14). */
  transferNow: 'qb.transfer.now',
  /** Bank top-ups (Q13a): finance records the money, another administrator approves it. */
  bankRecord: 'qb.topup.bank.record',
  bankApprove: 'qb.topup.bank.approve',
  /** Manual credits (Q18b): finance records them; the bank top-ups' approvers approve them. */
  manualRecord: 'qb.topup.manual.record',
  /** Q22: which reward methods a sponsor may use (F-S1: the operators'). */
  rewardMethods: 'qb.admin.reward-methods',
  redemptionVoid: 'qb.redemption.void',
  /** Q33: deciding disputed digital prizes and voiding open ones. */
  digitalResolve: 'qb.digital.resolve',
  /** Bank withdrawals (Q13b): paying them, and the account number in plain text to do so. */
  withdrawalPay: 'qb.withdrawal.pay',
  bankReveal: 'qb.payout.bank.reveal',
  /** Q48a: an operator's member (their own pages and acts), and headquarters' operator pages. */
  operatorUse: 'qb.operator.use',
  operatorsRead: 'qb.admin.operators.read',
  operatorsWrite: 'qb.admin.operators.write',
  spotCheck: 'qb.admin.spot-check',
  /** Q48c: AI prospecting (an operator's own, or headquarters'), and headquarters' view of every owner's. */
  prospectUse: 'qb.prospect.use',
  adminProspect: 'qb.admin.prospect',
  /** Q48b: recording the payment of an operator's settlement (finance; another administrator approves). */
  settlementPay: 'qb.operator.settlement.pay',
  /** Q70: every referral. */
  referralsRead: 'qb.admin.referrals.read',
  /** Q47c: the audience rules (content administrators) and every owner's price list (finance). */
  audienceRulesWrite: 'qb.audience.rules.write',
  audiencePricesWrite: 'qb.audience.prices.write',
  /** Q87: fees charged by hand and invoices voided (finance; another administrator approves), and a sponsor's billing. */
  feeManage: 'qb.fee.manage',
  feeBilling: 'qb.fee.billing',
  /** F-C10: the AI help's conversations handed to a person (content administrators, the operators' members). */
  adminHelp: 'qb.admin.help',
  /** F-C10c: what the AI help did not solve (headquarters' content administrators only). */
  adminHelpGaps: 'qb.admin.help.gaps',
  /** F-M2: an operator's lead confirms a Sponsor and gives the onboarding platform fee Kudos. */
  feeCreditOnboarding: 'qb.fee-credit.onboarding.confirm',
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

/** The widths the policy `qb.sponsor-page-image` keeps (a sponsor page's cover, Q60a; its logo is the avatar, Q65b). */
export const SPONSOR_PAGE_IMAGE_VARIANTS = ['w256', 'w640', 'w1280']

/** Taking a sponsor's page down (Q60a): headquarters', and an operator's for the sponsors it looks after. */
export const SPONSOR_PAGE_PROCESS = {
  takedown: 'QB_SPONSOR_PAGE_TAKEDOWN',
  operatorTakedown: 'QB_OPERATOR_SPONSOR_PAGE_TAKEDOWN',
  version: 1,
} as const

/** Taking a sponsor's product down and putting it back (Q60b): headquarters', and an operator's for its sponsors. */
export const SPONSOR_PRODUCT_PROCESS = {
  takedown: 'QB_PRODUCT_TAKEDOWN',
  restore: 'QB_PRODUCT_RESTORE',
  operatorTakedown: 'QB_OPERATOR_PRODUCT_TAKEDOWN',
  operatorRestore: 'QB_OPERATOR_PRODUCT_RESTORE',
  version: 1,
} as const

/** Closing a publication's reports (Q49a): headquarters', and an operator's for its sponsors. */
/** Q73d-2: a reviewer's AI comparison of a publication's translations (`content.ai.AiProcesses`). */
export const TRANSLATION_CHECK_PROCESS = 'QB_AI_TRANSLATION_CHECK_REQUEST'

/** F-S11: headquarters' exceptions to the rule on foreign videos (run on the platform's process page). */
export const REGION_PROCESS = {
  add: 'QB_REGION_EXCEPTION_ADD',
  expirySet: 'QB_REGION_EXCEPTION_EXPIRY_SET',
  revoke: 'QB_REGION_EXCEPTION_REVOKE',
  version: 1,
} as const

/** F-S12: taking a shared video down (the provider's operator, or headquarters; the platform's process page). */
export const SHARED_VIDEO_PROCESS = {
  takedown: 'QB_SHARED_VIDEO_TAKEDOWN',
  version: 1,
} as const

/** F-M3: revoking a Sponsor's company verification (a second factor is asked for by the platform). */
export const SPONSOR_VERIFICATION_PROCESS = {
  revoke: 'QB_SPONSOR_VERIFICATION_REVOKE',
} as const

/** F-S9: headquarters' upkeep of the word list of topics (run on the platform's process page). */
export const TOPIC_PROCESS = {
  add: 'QB_TOPIC_ADD',
  rename: 'QB_TOPIC_RENAME',
  deactivate: 'QB_TOPIC_DEACTIVATE',
  reactivate: 'QB_TOPIC_REACTIVATE',
  merge: 'QB_TOPIC_MERGE',
  version: 1,
} as const

export const REPORT_PROCESS = {
  resolve: 'QB_REPORT_RESOLVE',
  operatorResolve: 'QB_OPERATOR_REPORT_RESOLVE',
  version: 1,
} as const

/** A sponsor product's states (`QbSponsorProducts`). */
export const SPONSOR_PRODUCT_STATUSES = ['LISTED', 'UNLISTED', 'TAKEN_DOWN'] as const

/** A sponsor page's states (`QbSponsorPages`): waiting for its review, shown, and the rest. */
export const SPONSOR_PAGE_PENDING = 'PENDING'
export const SPONSOR_PAGE_PUBLISHED = 'PUBLISHED'
export const SPONSOR_PAGE_STATUSES = [SPONSOR_PAGE_PENDING, SPONSOR_PAGE_PUBLISHED, 'DRAFT', 'REJECTED', 'TAKEN_DOWN'] as const

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
  /** F-S2b3a: the knowledge maps, and one map's review and lit nodes. */
  trees: '/qb/trees',
  tree: (treeId: string) => `/qb/trees/${encodeURIComponent(treeId)}`,
  /** F-S3e: support's lookup of one person's lighting of one map. */
  treeSupport: '/qb/tree-support',
  /** F-S5b: the lookup of certificates by number or holder (support, and an operator's members). */
  certificates: '/qb/certificates',
  reviews: '/qb/reviews',
  /** F-C2: the sponsors' usage by month (`?sponsor=` for one). */
  usage: '/qb/usage',
  publication: (publicationId: string) => `/qb/reviews/publications/${encodeURIComponent(publicationId)}`,
  sponsor: (userId: string) => `/qb/reviews/sponsors/${encodeURIComponent(userId)}`,
  /** Sponsors' pages (Q60a-3): every one, and one page's content, review and takedown. */
  sponsorPages: '/qb/sponsor-pages',
  sponsorPage: (userId: string) => `/qb/sponsor-pages/${encodeURIComponent(userId)}`,
  /** Sponsors' products (Q60b-3), to take down and put back. */
  sponsorProducts: '/qb/sponsor-products',
  /** Q49a: takers' reports of quizzes. */
  abuseReports: '/qb/abuse-reports',
  /** F-S11: foreign videos and mainland China: the exceptions and the refused starts (the daily check links here). */
  region: '/qb/region',
  /** F-S9: the word list of topics. */
  topics: '/qb/topics',
  /** F-S8: the figures of blocking. */
  blocks: '/qb/blocks',
  /** F-S13: the suspicious inflation of exposures. */
  exposure: '/qb/exposure',
  /** F-S14: the ledger of shared videos' uses. */
  videoUses: '/qb/video-uses',
  /** F-S12: the videos Sponsors share, and one video's review and takedown. */
  sharedVideos: '/qb/shared-videos',
  sharedVideo: (sharedVideoId: string) => `/qb/shared-videos/${encodeURIComponent(sharedVideoId)}`,
  /** F-M3: the Sponsors' company verifications, and one verification. */
  sponsorVerifications: '/qb/sponsor-verifications',
  sponsorVerification: (verificationId: string) => `/qb/sponsor-verifications/${encodeURIComponent(verificationId)}`,

  /** Q82: one conversation between a taker and a sponsor, read only (from a report of it). */
  conversation: (conversationId: string) => `/qb/conversations/${encodeURIComponent(conversationId)}`,
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
  /** Q87: the platform's fees: charges, invoices and the requests waiting for approval. */
  fees: '/qb/finance/fees',
  feesWhere: (filter: Record<string, string>) => `/qb/finance/fees?${new URLSearchParams(filter)}`,
  feeRequest: (requestId: string) => `/qb/finance/fees/requests/${encodeURIComponent(requestId)}`,
  /** The legal texts: publishing the drafts and withdrawing announced versions (Q7b). */
  legal: '/qb/legal',
  /** Announcements and notices: writing, sending and withdrawing them, with their statistics (Q7c). */
  broadcasts: '/qb/broadcasts',
  /** AI generation (Q8a): usage, failures, the switch and the limits. */
  ai: '/qb/ai',
  /** Q48a, headquarters: the operators, one operator, and the spot checks of their reviews. */
  operators: '/qb/operators',
  /** F-S1b: where a session lands: the platform's home, or the operator's in the Operator Management System. */
  home: '/qb/home',
  operator: (operatorId: string) => `/qb/operators/${encodeURIComponent(operatorId)}`,
  operatorReviews: '/qb/operator-reviews',
  /** Q48a, an operator's own pages: home (totals and team) and the sponsors. */
  operatorHome: '/qb/operator',
  operatorSponsors: '/qb/operator/sponsors',
  /** Q87b: the billing of the operator's sponsors. */
  operatorBilling: '/qb/operator/billing',
  /** Q48c: AI prospecting: the businesses found, one business's draft, and the settings. */
  prospects: '/qb/prospects',
  prospect: (prospectId: string) => `/qb/prospects/${encodeURIComponent(prospectId)}`,
  prospectSettings: '/qb/prospects/settings',
  /** Q48b: the operators' settlements (finance), and an operator's own. */
  settlements: '/qb/finance/operator-settlements',
  operatorSettlements: '/qb/operator/settlements',
  /** Q48e-2: the operator's account (balance, bank account, withdrawals), and the withdrawals (finance). */
  operatorAccount: '/qb/operator/account',
  operatorWithdrawals: '/qb/finance/operator-withdrawals',
  referrals: '/qb/referrals',
  /** Q47c: the audience rules, every owner's price list (headquarters), and the operator's own. */
  audienceRules: '/qb/audience/rules',
  audiencePrices: '/qb/audience/prices',
  operatorAudiencePrices: '/qb/operator/audience-prices',
  /** F-M6: every operator's distribution price (headquarters), the operator's own, and the settlements (finance). */
  distributionPrices: '/qb/distribution/prices',
  operatorDistributionPrice: '/qb/operator/distribution-price',
  distributionSettlements: '/qb/finance/distribution-settlements',
  /** F-M8: every operator's exposure price (headquarters), the operator's own, and the settlements (finance). */
  exposurePrices: '/qb/exposure-fee/prices',
  operatorExposurePrice: '/qb/operator/exposure-price',
  exposureSettlements: '/qb/finance/exposure-settlements',
  /** The platform's own pages. */
  tasks: '/tasks',
  /** F-C10c: the AI help's conversations handed to a person (headquarters every one, an operator its own). */
  help: '/qb/help',
  helpThread: (threadId: string) => `/qb/help/${encodeURIComponent(threadId)}`,
  /** F-C10c: what the AI help did not solve, by month (`?month=`). */
  helpGaps: '/qb/help-gaps',
  /** F-C10c: the AI help's usage manuals: publishing and loading the repository's text. */
  helpManuals: '/qb/help-manuals',
} as const

/** The publisher kind of the sponsors' console (S0, `PublisherKind.SPONSOR`); other kinds come with their modules. */
export const SPONSOR_KIND = 'SPONSOR'

/**
 * Q47c (backend `audience.AudiencePricingProcesses`, docs/quizbuks/plans/Q47c-audience-billing.md): the audience rules
 * as a product line and their prices. The kinds are the code's (`audience.AudienceCriteria.ALL`, in its order; the
 * sponsor console's `qb/audience.ts`): a kind without a record is open and charged for.
 */
export const AUDIENCE_RULE_KEYS = ['gender', 'age', 'occupation', 'interest'] as const

export const AUDIENCE_PRICING = {
  rulesSet: 'QB_AUDIENCE_RULES_SET',
  pricesSet: 'QB_AUDIENCE_PRICES_SET',
  operatorPricesSet: 'QB_OPERATOR_AUDIENCE_PRICES_SET',
  rulesTemplate: 'qb.admin.audience-rules',
  pricesTemplate: 'qb.admin.audience-prices',
  operatorPricesTemplate: 'qb.operator.audience-prices',
} as const

/** F-M6 (backend `fee.DistributionPrices`, `qb.admin.distribution-*`): the distribution fee's prices and settlements. */
export const DISTRIBUTION = {
  priceSet: 'QB_DISTRIBUTION_PRICE_SET',
  operatorPriceSet: 'QB_OPERATOR_DISTRIBUTION_PRICE_SET',
  pricesTemplate: 'qb.admin.distribution-prices',
  operatorPriceTemplate: 'qb.operator.distribution-price',
  settlementsTemplate: 'qb.admin.distribution-settlements',
  /** Review L5: the caller operator's own settlements (its frozen fees), through its membership. */
  operatorSettlementsTemplate: 'qb.operator.distribution-settlements',
} as const

/** F-M8 (backend `fee.ExposurePrices`, `qb.admin.exposure-*`): the exposure fee's prices and settlements. */
export const EXPOSURE_FEE = {
  priceSet: 'QB_EXPOSURE_PRICE_SET',
  operatorPriceSet: 'QB_OPERATOR_EXPOSURE_PRICE_SET',
  pricesTemplate: 'qb.admin.exposure-prices',
  operatorPriceTemplate: 'qb.operator.exposure-price',
  settlementsTemplate: 'qb.admin.exposure-settlements',
  operatorSettlementsTemplate: 'qb.operator.exposure-settlements',
} as const

/**
 * F-C2: whether a usage meter counts bytes (shown in MB). The meters are listed on the server only
 * (`usage.UsageMeters`, the dictionary `usage-meter`); those of bytes are named `….bytes` / `…-bytes` there.
 */
export const isByteMeter = (meter: string) => /[.-]bytes$/.test(meter)

/**
 * F-C10c: the AI help (`help.HelpProcesses`, `help.HelpMaterialsProcesses`; docs/quizbuks/plans/impl/F-C10.md): the
 * people's replies, the gaps report, the usage, and the usage manuals.
 */
export const HELP = {
  reply: 'QB_HELP_REPLY',
  publish: 'QB_HELP_PUBLISH',
  load: 'QB_HELP_MANUAL_LOAD',
  threads: 'qb.admin.help-threads',
  messages: 'qb.admin.help-messages',
  gaps: 'qb.admin.help-gaps',
  usage: 'qb.admin.help-usage',
  manual: 'urn:jabiz:dataset:default:QbHelpManual',
  material: 'urn:jabiz:dataset:default:QbHelpMaterial',
  /** 422: the draft was edited since the repository's text was last loaded (keep it, or replace it). */
  manualEdited: 'QB_HELP_MANUAL_EDITED',
} as const
