import type { APIRequestContext, Page } from '@playwright/test'
import { adminToken, expect, insert, PASSWORD, rows, run, setControlledParam, tokenOf, verifiedUser } from './support'

/*
 * Data for the journal export and receipt tests (docs/quizbuks/plans/Q9d-journal-receipts.md). The export needs a
 * person with a verified address and finance's export permission; the finance role asks for a second factor, so the
 * tests make an accountant of their own: a verified user given the tests' role QB_E2E_FINANCE (no second factor),
 * which the end-to-end job adds to QB_ADMIN_ROLES (as QB_E2E_PUBLISHER, Q9c). The server reconciles every minute
 * there (QB_RECONCILE_RUN_CRON): the export waits for a clean reconciliation after its month.
 */

export const ACCOUNTANT_ROLE = 'QB_E2E_FINANCE'
const ACCOUNTANT_PERMISSIONS = ['qb.admin.finance.read', 'qb.admin.users.read', 'qb.journal.export',
  'qb.journal.confirm', 'file.generated.read']

const ROLES = 'urn:jabiz:dataset:platform:SecRole'
const ROLE_PERMISSIONS = 'urn:jabiz:dataset:platform:SecRolePermission'
const USERS = 'urn:jabiz:dataset:platform:SecUser'
const USER_ROLES = 'urn:jabiz:dataset:platform:SecUserRole'

/** The accounts of the chart (backend `QbLedger`) and the codes the tests map them to. */
export const ACCOUNTS = ['1110', '1120', '2110', '2120', '2130', '4110', '5110']
export const codeOf = (account: string) => `E2E-${account}`

let mapped = false

/** Every account mapped to a code of the accounting system (with four eyes), once per run. */
export async function mapAccounts(request: APIRequestContext) {
  if (mapped) return
  for (const account of ACCOUNTS) {
    await setControlledParam(request, `qb.journal.account.${account}`, codeOf(account))
  }
  mapped = true
}

let issuer = false

/** The receipts' issuer set (with four eyes), once per run. */
export async function issuerSet(request: APIRequestContext) {
  if (issuer) return
  await setControlledParam(request, 'qb.issuer.name', 'QuizBuks E2E K.K.')
  await setControlledParam(request, 'qb.issuer.address', '1-2-3 Chiyoda, Tokyo')
  await setControlledParam(request, 'qb.issuer.registration-no', 'T0000000000000')
  issuer = true
}

/** A user with a verified address holding the tests' accountant role; returns the address and an admin token. */
export async function accountant(request: APIRequestContext) {
  const email = await verifiedUser(request, 'sponsor', 'Kei')
  const admin = await adminToken(request)
  let roleId = (await rows(request, admin, ROLES, [{ field: 'roleCode', op: 'eq', value: ACCOUNTANT_ROLE }], 1))[0]?.id
  if (!roleId) {
    roleId = (await insert(request, admin, ROLES, { roleCode: ACCOUNTANT_ROLE, labels: { en: 'E2E accountant' },
      enabled: true })).id
  }
  const held = (await rows(request, admin, ROLE_PERMISSIONS, [{ field: 'roleId', op: 'eq', value: roleId }]))
    .map((row) => row.attributes.permission)
  for (const permission of ACCOUNTANT_PERMISSIONS.filter((p) => !held.includes(p))) {
    await insert(request, admin, ROLE_PERMISSIONS, { roleId, permission })
  }
  const userId = (await rows(request, admin, USERS, [{ field: 'userName', op: 'eq', value: email }], 1))[0].id
  await insert(request, admin, USER_ROLES, { userId, roleId })
  return { email, token: await tokenOf(request, email, PASSWORD, 'admin') }
}

/** Signs a user in at the administration. */
export async function signInAdminAs(page: Page, email: string) {
  await page.goto('/admin/login')
  await page.getByPlaceholder('User name', { exact: true }).fill(email)
  await page.getByPlaceholder('Password', { exact: true }).fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).not.toHaveURL(/\/admin\/login/)
}

/**
 * A correction posted by hand, booked in the previous month (in Japan): the earliest posting of a new database, so
 * that the first export is a month that has ended. Between the bank and Stripe's balance: no wallet changes, the
 * reconciliation stays clean.
 */
export async function postingLastMonth(request: APIRequestContext) {
  const now = new Date(Date.now() + 9 * 3_600_000)
  const lastMonth = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 2) - 9 * 3_600_000)
  return run(request, await adminToken(request), 'LEDGER_POST', {
    bookingTime: lastMonth.toISOString(), description: 'E2E correction', reference: 'e2e-journal',
    entries: [{ accountCode: '1120', direction: 'DEBIT', amount: 1 },
      { accountCode: '1110', direction: 'CREDIT', amount: 1 }] })
}

/** What the next export would be, as the accountant previews it. */
export async function previewOf(request: APIRequestContext, token: string) {
  return run(request, token, 'QB_JOURNAL_PREVIEW', {}) as Promise<{ month: string; periodEnded: boolean;
    reconOk: boolean; ready: boolean; unmapped: string[]; detailParts: number }>
}
