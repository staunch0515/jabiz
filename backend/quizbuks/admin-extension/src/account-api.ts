import { runProcess, runQuery } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { ACCOUNT_PROCESS, TEMPLATE } from './ids'

/*
 * The operators' accounts, withdrawals and plans (backend `operator.OperatorWithdrawals` and `OperatorPlans`,
 * docs/quizbuks/plans/Q48e-operator-isolation.md, Q48e-2), typed by hand after the templates and processes. Money
 * stays with the platform: each month's settlement is credited to the operator's account, its lead withdraws to its
 * bank account, finance records the bank payment and another administrator approves it. The amounts are yen of the
 * ledger; the operator's pages show them as points (`usePoints`), finance's as JPY (`money`).
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const amount = (value: unknown): string => (value == null || value === '' ? '0' : String(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

/** The five plans (`urn:jabiz:dict:quizbuks:operator-plan`); without an approved change an operator is on plan 1. */
export const PLANS = ['1', '2', '3', '4', '5'] as const
export const DEFAULT_PLAN = '1'

/** A withdrawal's states (`urn:jabiz:dict:quizbuks:operator-withdrawal-status`). */
export const WITHDRAWAL_STATUSES = ['REQUESTED', 'PAYING', 'PAID', 'REJECTED', 'CANCELLED'] as const

/** The kinds of a Japanese bank account (`urn:jabiz:dict:quizbuks:bank-account-type`). */
export const ACCOUNT_TYPES = ['ORDINARY', 'CURRENT', 'SAVINGS'] as const

/** The month (`yyyy-MM`) in Japan now: the settlements' and the plans' months are Japan's. */
export function currentMonthInJapan(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit' })
    .format(now).slice(0, 7)
}

/** The month after `month` (`yyyy-MM`). */
export function nextMonth(month: string): string {
  const [year, m] = month.split('-').map(Number)
  return m === 12 ? `${year + 1}-01` : `${year}-${String(m + 1).padStart(2, '0')}`
}

/** A platform share (0–1) as a percentage ("50%"). */
export function percent(share: string | null): string {
  if (share == null) return ''
  const value = Number(share)
  return Number.isFinite(value) ? `${Number((value * 100).toFixed(2))}%` : share
}

/** The row of `qb.operator.account`: the caller operator's balance and bank account (the number masked). */
export interface OperatorAccount {
  operatorId: string
  /** Whether the caller leads the operator: only the lead sets the bank account and withdraws. */
  lead: boolean
  credited: string
  withdrawn: string
  balance: string
  /** The least one withdrawal may take (`qb.operator.withdrawal.min`). */
  minimum: string | null
  /** For how long after a change of the bank account no withdrawal is possible. */
  cooldownHours: string | null
  bank: {
    bankName: string
    branchName: string
    accountType: string
    accountNumber: string
    accountHolder: string
    changedAt: string | null
  } | null
}

/** The caller's operator's account; null when the caller is no member. */
export async function readAccount(): Promise<OperatorAccount | null> {
  const page = await runQuery<Row>(TEMPLATE.operatorAccount, { limit: 1 })
  const row = page.items[0]
  if (!row) return null
  return {
    operatorId: String(row.operatorId),
    lead: bool(row.lead),
    credited: amount(row.credited),
    withdrawn: amount(row.withdrawn),
    balance: amount(row.balance),
    minimum: str(row.minimum),
    cooldownHours: str(row.cooldownHours),
    bank: row.bankName == null ? null : {
      bankName: String(row.bankName),
      branchName: String(row.branchName ?? ''),
      accountType: String(row.accountType ?? ''),
      accountNumber: String(row.accountNumber ?? ''),
      accountHolder: String(row.accountHolder ?? ''),
      changedAt: str(row.changedAt),
    },
  }
}

/** A plan's terms in force: the monthly fixed fee and the platform's share (0–1). */
export interface PlanTerms {
  plan: string
  fixedFee: string
  platformShare: string | null
}

/** A change of an operator's plan from a month on, approved by another administrator or not yet. */
export interface PlanChange {
  plan: string
  effectiveMonth: string
  status: string
  proposedAt: string | null
}

/** `qb.operator.plans`: the five plans' terms, and the caller's operator's changes of plan. */
export async function readPlans(): Promise<{ terms: PlanTerms[]; changes: PlanChange[] }> {
  const page = await runQuery<Row>(TEMPLATE.ownPlans, { limit: 100 })
  const terms: PlanTerms[] = []
  const changes: PlanChange[] = []
  for (const row of page.items) {
    if (row.kind === 'TERMS') {
      terms.push({ plan: String(row.plan), fixedFee: amount(row.fixedFee), platformShare: str(row.platformShare) })
    } else if (row.kind === 'CHANGE') {
      changes.push({ plan: String(row.plan), effectiveMonth: String(row.effectiveMonth ?? ''),
        status: String(row.status ?? ''), proposedAt: str(row.proposedAt) })
    }
  }
  terms.sort((a, b) => a.plan.localeCompare(b.plan))
  return { terms, changes }
}

/**
 * The plan in effect in `month`, as the settlement takes it: the approved change with the latest month not after it,
 * else plan 1. (Only shown; the server settles.)
 */
export function planInEffect(changes: readonly Pick<PlanChange, 'plan' | 'effectiveMonth' | 'status'>[],
  month: string): string {
  let best: { plan: string; effectiveMonth: string } | null = null
  for (const change of changes) {
    if (change.status !== 'APPROVED' || change.effectiveMonth > month) continue
    if (!best || change.effectiveMonth > best.effectiveMonth) best = change
  }
  return best?.plan ?? DEFAULT_PLAN
}

/** The changes after `month` still to come into effect (waiting for approval or approved), the soonest first. */
export function laterChanges<C extends Pick<PlanChange, 'effectiveMonth' | 'status'>>(changes: readonly C[],
  month: string): C[] {
  return changes.filter((change) => change.effectiveMonth > month
    && (change.status === 'PENDING' || change.status === 'APPROVED'))
    .sort((a, b) => a.effectiveMonth.localeCompare(b.effectiveMonth))
}

/** A row of `qb.operator.withdrawals`: the caller operator's own, without who recorded the payment. */
export interface OwnWithdrawal {
  withdrawalId: string
  amount: string
  status: string
  requestedAt: string | null
  bankName: string | null
  accountNumber: string | null
  paidOn: string | null
  rejectReason: string | null
  closedAt: string | null
}

function toOwnWithdrawal(row: Row): OwnWithdrawal {
  return {
    withdrawalId: String(row.withdrawalId),
    amount: amount(row.amount),
    status: String(row.status ?? ''),
    requestedAt: str(row.requestedAt),
    bankName: str(row.bankName),
    accountNumber: str(row.accountNumber),
    paidOn: str(row.paidOn),
    rejectReason: str(row.rejectReason),
    closedAt: str(row.closedAt),
  }
}

/** The caller operator's withdrawals, the newest first. */
export async function readOwnWithdrawals(offset: number, limit: number, sorts?: SortSpec[]): Promise<Page<OwnWithdrawal>> {
  const page = await runQuery<Row>(TEMPLATE.ownWithdrawals, { sorts, offset, limit, count: true })
  return { total: page.total, items: page.items.map(toOwnWithdrawal) }
}

/** What setting the bank account sends (`QB_OPERATOR_BANK_ACCOUNT_SAVE`): the Zengin format, the holder in katakana. */
export interface BankAccountInput {
  bankCode: string
  bankName: string
  branchCode: string
  branchName: string
  accountType: string
  accountNumber: string
  accountHolder: string
}

/** Sets the operator's bank account (its lead, with a recent second factor); withdrawals wait for `usableFrom`. */
export const saveBankAccount = (input: BankAccountInput) =>
  runProcess<{ bankAccountId: string; usableFrom: string | null }>(ACCOUNT_PROCESS.bankAccountSave, { ...input })

/** Withdraws a whole amount from the balance to the bank account (its lead, with a recent second factor). */
export const requestWithdrawal = (amount: number) =>
  runProcess<{ withdrawalId: string; status: string; amount: string; approvalRequestId: string | null }>(
    ACCOUNT_PROCESS.withdrawalRequest, { amount })

/** Cancels a withdrawal before finance recorded its payment (`REQUESTED`). */
export const cancelWithdrawal = (withdrawalId: string) =>
  runProcess<unknown>(ACCOUNT_PROCESS.withdrawalCancel, { withdrawalId })

/** A row of `qb.admin.operator-withdrawals`: every operator's, with the bank account it goes to. */
export interface OperatorWithdrawal {
  withdrawalId: string
  operatorId: string
  operatorName: string | null
  amount: string
  status: string
  requestedBy: string | null
  requestedAt: string | null
  bankCode: string | null
  bankName: string | null
  branchCode: string | null
  branchName: string | null
  accountType: string | null
  accountNumber: string | null
  accountHolder: string | null
  paidOn: string | null
  bankReference: string | null
  paymentRecordedBy: string | null
  approvalRequestId: string | null
  paidAt: string | null
  rejectReason: string | null
}

function toOperatorWithdrawal(row: Row): OperatorWithdrawal {
  return {
    withdrawalId: String(row.withdrawalId),
    operatorId: String(row.operatorId ?? ''),
    operatorName: str(row.operatorName),
    amount: amount(row.amount),
    status: String(row.status ?? ''),
    requestedBy: str(row.requestedBy),
    requestedAt: str(row.requestedAt),
    bankCode: str(row.bankCode),
    bankName: str(row.bankName),
    branchCode: str(row.branchCode),
    branchName: str(row.branchName),
    accountType: str(row.accountType),
    accountNumber: str(row.accountNumber),
    accountHolder: str(row.accountHolder),
    paidOn: str(row.paidOn),
    bankReference: str(row.bankReference),
    paymentRecordedBy: str(row.paymentRecordedBy),
    approvalRequestId: str(row.approvalRequestId),
    paidAt: str(row.paidAt),
    rejectReason: str(row.rejectReason),
  }
}

/** Every operator's withdrawals (finance), the newest first; only those in `status` when given. */
export async function readOperatorWithdrawals(status: string | null, offset: number, limit: number,
  sorts?: SortSpec[]): Promise<Page<OperatorWithdrawal>> {
  const page = await runQuery<Row>(TEMPLATE.operatorWithdrawals, {
    params: status ? { status } : {}, sorts, offset, limit, count: true,
  })
  return { total: page.total, items: page.items.map(toOperatorWithdrawal) }
}

/** Records the bank payment for another administrator's approval (`QB_OPERATOR_WITHDRAWAL_PAY`). */
export const payWithdrawal = (withdrawalId: string, paidOn: string, bankReference: string) =>
  runProcess<{ withdrawalId: string; status: string; approvalRequestId: string | null }>(ACCOUNT_PROCESS.withdrawalPay,
    { withdrawalId, paidOn, bankReference })

/** Rejects a withdrawal with a reason the operator reads (`QB_OPERATOR_WITHDRAWAL_REJECT`). */
export const rejectWithdrawal = (withdrawalId: string, reason: string) =>
  runProcess<unknown>(ACCOUNT_PROCESS.withdrawalReject, { withdrawalId, reason })

/** A row of `qb.admin.operator-plans`: one operator's change of plan. */
export interface OperatorPlanChange extends PlanChange {
  planChangeId: string
  operatorId: string
  reason: string | null
  proposedBy: string | null
  approvalRequestId: string | null
  decidedAt: string | null
}

/** One operator's changes of plan (headquarters), the newest first. */
export async function readOperatorPlans(operatorId: string): Promise<OperatorPlanChange[]> {
  const page = await runQuery<Row>(TEMPLATE.operatorPlans, { params: { operatorId }, limit: 100 })
  return page.items.map((row) => ({
    planChangeId: String(row.planChangeId),
    operatorId: String(row.operatorId ?? ''),
    plan: String(row.plan ?? ''),
    effectiveMonth: String(row.effectiveMonth ?? ''),
    reason: str(row.reason),
    status: String(row.status ?? ''),
    proposedBy: str(row.proposedBy),
    proposedAt: str(row.proposedAt),
    approvalRequestId: str(row.approvalRequestId),
    decidedAt: str(row.decidedAt),
  }))
}

/** Proposes an operator's plan from a later month (`QB_OPERATOR_PLAN_SET`); another administrator approves it. */
export const setOperatorPlan = (input: { operatorId: string; plan: string; effectiveMonth: string; reason: string }) =>
  runProcess<{ planChangeId: string; status: string; approvalRequestId: string | null }>(ACCOUNT_PROCESS.planSet,
    { ...input })

/** The query keys of the operator's account page and of finance's withdrawals. */
export const ACCOUNT_KEY = ['qb-ext', 'operator-account'] as const
export const OPERATOR_WITHDRAWALS_KEY = ['qb-ext', 'operator-withdrawals'] as const
