import { api, runProcess, runQuery, unwrap, type Filter } from '@jabiz/admin'
import type { Page } from './finance-api'
import { DATASET, PROCESS, TEMPLATE } from './ids'

/*
 * Bank withdrawals (Q13b; docs/quizbuks/plans/Q13-bank-transfer.md section 3): the template `qb.admin.bank-withdrawals`
 * (finance) and the processes that record one as paid or returned, typed by hand after the Java records.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const amount = (value: unknown): string => (value == null || value === '' ? '0' : String(value))

/** A row of `qb.admin.bank-withdrawals`: the account as it was asked for, without its number. */
export interface BankWithdrawal {
  transferId: string
  userId: string
  amount: string
  fee: string
  net: string
  status: string
  createdAt: string | null
  bankCode: string
  bankName: string
  branchCode: string
  branchName: string
  accountType: string
  accountHolder: string
  approvalRequestId: string | null
  paidOn: string | null
  paidReference: string | null
  paidBy: string | null
  failureCode: string | null
  failureMessage: string | null
}

function toWithdrawal(row: Row): BankWithdrawal {
  return {
    transferId: String(row.transferId),
    userId: String(row.userId ?? ''),
    amount: amount(row.amount),
    fee: amount(row.fee),
    net: amount(row.net),
    status: String(row.status ?? ''),
    createdAt: str(row.createdAt),
    bankCode: String(row.bankCode ?? ''),
    bankName: String(row.bankName ?? ''),
    branchCode: String(row.branchCode ?? ''),
    branchName: String(row.branchName ?? ''),
    accountType: String(row.accountType ?? ''),
    accountHolder: String(row.accountHolder ?? ''),
    approvalRequestId: str(row.approvalRequestId),
    paidOn: str(row.paidOn),
    paidReference: str(row.paidReference),
    paidBy: str(row.paidBy),
    failureCode: str(row.failureCode),
    failureMessage: str(row.failureMessage),
  }
}

export async function readBankWithdrawals(status: string | null, offset: number, limit: number):
  Promise<Page<BankWithdrawal>> {
  const filters: Filter[] = status ? [{ field: 'status', op: 'eq', value: status }] : []
  const page = await runQuery<Row>(TEMPLATE.bankWithdrawals, { filters, offset, limit, count: true })
  return { total: page.total, items: page.items.map(toWithdrawal) }
}

/** The account number of a withdrawal in plain text: the server checks the permission and records the display. */
export async function revealAccountNumber(transferId: string): Promise<string> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/reveal', {
    params: { path: { resourceId: DATASET.transfer } },
    body: { id: transferId, field: 'accountNumber' },
  }))
  return answer.value == null ? '' : String(answer.value)
}

/** `BankWithdrawals.PaidInput`: the statement's day (`YYYY-MM-DD`, in Japan) and its reference. */
export const markPaid = (input: { transferId: string; paidOn: string; paidReference: string }) =>
  runProcess<{ transferId: string; status: string }>(PROCESS.withdrawalPaid, input)

/** `BankWithdrawals.ReturnedInput`: why the bank returned it. */
export const markReturned = (input: { transferId: string; reason: string }) =>
  runProcess<{ transferId: string; status: string }>(PROCESS.withdrawalReturned, input)
