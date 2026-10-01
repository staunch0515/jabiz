import { expect, type APIRequestContext, type Page } from '@playwright/test'

/**
 * The books the finance tests work in, set up through the API as people would: finance setup by the administrator,
 * an accountant and a controller, the approval rule published by the controller (four eyes), fiscal year 2026 and
 * the accounts the tests use. Everything is made only when missing, so the suite runs again on the same database.
 */
export interface User {
  userName: string
  password: string
}

export const ADMIN: User = {
  userName: process.env.E2E_ADMIN_USER ?? 'admin',
  password: process.env.E2E_ADMIN_PASSWORD ?? 'admin-password-1',
}
/**
 * The test users are made on the server under test with known passwords: only a server on this machine (a CI job or
 * a developer's), unless E2E_ALLOW_REMOTE is set for a disposable environment; the passwords can be set too.
 */
export const ACCOUNTANT: User = {
  userName: 'e2e-accountant',
  password: process.env.E2E_ACCOUNTANT_PASSWORD ?? 'e2e-accountant-password-1',
}
export const CONTROLLER: User = {
  userName: 'e2e-controller',
  password: process.env.E2E_CONTROLLER_PASSWORD ?? 'e2e-controller-password-1',
}

function requireDisposableServer() {
  const host = new URL(process.env.E2E_BASE_URL ?? 'http://localhost:8080').hostname
  if (!['localhost', '127.0.0.1', '::1', '[::1]'].includes(host) && !process.env.E2E_ALLOW_REMOTE) {
    throw new Error(`E2E_BASE_URL points at ${host}: the tests make users with known passwords there. ` +
      'Set E2E_ALLOW_REMOTE=1 only for a disposable environment.')
  }
}

const ACCOUNTS = [
  { accountCode: '1010', accountName: 'Cash - Operating', financialType: 'ASSET', normalBalance: 'DEBIT',
    statementLine: 'Cash and cash equivalents', controlClass: 'BANK' },
  { accountCode: '2100', accountName: 'Accrued Liabilities', financialType: 'LIABILITY', normalBalance: 'CREDIT',
    statementLine: 'Accrued liabilities' },
  { accountCode: '6400', accountName: 'Professional Fees', financialType: 'EXPENSE', normalBalance: 'DEBIT',
    statementLine: 'Operating expenses' },
  { accountCode: '6500', accountName: 'Software Subscriptions', financialType: 'EXPENSE', normalBalance: 'DEBIT',
    statementLine: 'Operating expenses' },
]

/** A name no earlier run used: the tables only grow. */
export function unique(prefix: string): string {
  return `${prefix} ${Date.now().toString(36).toUpperCase()}${Math.floor(Math.random() * 1296).toString(36).toUpperCase()}`
}

export async function token(request: APIRequestContext, user: User): Promise<string> {
  const response = await request.post('/api/auth/login', { data: user })
  expect(response.status(), await response.text()).toBe(200)
  return (await response.json()).accessToken
}

async function run(request: APIRequestContext, bearer: string, process: string, input: unknown) {
  const response = await request.post(`/api/processes/${process}/latest`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Idempotency-Key': crypto.randomUUID() },
    data: input,
  })
  return { status: response.status(), body: await response.json().catch(() => ({})) }
}

async function find(request: APIRequestContext, bearer: string, dataset: string, field: string, value: unknown) {
  const response = await request.post(`/api/datasets/${encodeURIComponent(dataset)}/query`, {
    headers: { Authorization: `Bearer ${bearer}` },
    data: { filters: [{ field, op: 'eq', value }], limit: 10 },
  })
  expect(response.status(), await response.text()).toBe(200)
  return ((await response.json()).items ?? []) as { id: string; attributes: Record<string, unknown> }[]
}

async function ensureUser(request: APIRequestContext, admin: string, user: User, roleCode: string) {
  if ((await find(request, admin, 'urn:jabiz:dataset:platform:SecUser', 'userName', user.userName)).length === 0) {
    const created = await run(request, admin, 'SEC_USER_CREATE', { ...user, displayName: user.userName })
    expect(created.status, JSON.stringify(created.body)).toBe(200)
  }
  const [account] = await find(request, admin, 'urn:jabiz:dataset:platform:SecUser', 'userName', user.userName)
  const [role] = await find(request, admin, 'urn:jabiz:dataset:platform:SecRole', 'roleCode', roleCode)
  expect(role, `role ${roleCode}`).toBeTruthy()
  const assigned = await find(request, admin, 'urn:jabiz:dataset:platform:SecUserRole', 'userId', account.id)
  if (!assigned.some((a) => a.attributes.roleId === role.id)) {
    const response = await request.post(`/api/datasets/${encodeURIComponent('urn:jabiz:dataset:platform:SecUserRole')}/commit`, {
      headers: { Authorization: `Bearer ${admin}` },
      data: { changes: [{ action: 'INSERT', attributes: { userId: account.id, roleId: role.id } }] },
    })
    expect(response.status(), await response.text()).toBe(200)
  }
}

let prepared = false

export async function prepareBooks(request: APIRequestContext) {
  if (prepared) return
  requireDisposableServer()
  const admin = await token(request, ADMIN)
  const setup = await run(request, admin, 'FIN_SETUP', {})
  expect(setup.status, JSON.stringify(setup.body)).toBe(200)
  await ensureUser(request, admin, ACCOUNTANT, 'Accountant')
  await ensureUser(request, admin, CONTROLLER, 'Controller')

  const change = setup.body.output?.approvalRuleChange
  if (change) {
    const published = await run(request, await token(request, CONTROLLER), 'CONTROL_CHANGE_PUBLISH', { changeId: change })
    expect(published.status, JSON.stringify(published.body)).toBe(200)
  }
  const year = await run(request, admin, 'FIN_FISCAL_YEAR_CREATE', { fiscalYear: 2026, adjustmentPeriod: true })
  expect([200, 422], JSON.stringify(year.body)).toContain(year.status)

  const lookup = await request.post('/api/queries/finance.gl.account_lookup', {
    headers: { Authorization: `Bearer ${admin}` },
    data: { limit: 1000 },
  })
  const existing = new Set(((await lookup.json()).items as { accountCode: string }[]).map((a) => a.accountCode))
  for (const account of ACCOUNTS.filter((a) => !existing.has(a.accountCode))) {
    const created = await run(request, admin, 'FIN_ACCOUNT_CREATE', account)
    expect(created.status, JSON.stringify(created.body)).toBe(200)
  }
  prepared = true
}

/** Signs in on the sign-in page, then opens the journal register from the menu. */
export async function signIn(page: Page, user: User) {
  await page.goto('/login')
  await page.getByPlaceholder('User name').fill(user.userName)
  await page.getByPlaceholder('Password').fill(user.password)
  await page.getByRole('button', { name: /Sign\s*in/ }).click()
  await page.waitForURL((url) => !url.pathname.startsWith('/login'))
  await page.getByRole('menuitem', { name: 'General ledger' }).click()
  await page.getByRole('link', { name: 'Journal entries' }).click()
  await page.waitForURL('**/gl/journals')
}

/** Pastes text into a cell as a spreadsheet's copy arrives: a paste event carrying text/plain. */
export async function pasteInto(page: Page, label: string, text: string) {
  await page.getByLabel(label, { exact: true }).evaluate((element, data) => {
    const transfer = new DataTransfer()
    transfer.setData('text/plain', data)
    element.dispatchEvent(new ClipboardEvent('paste', { clipboardData: transfer, bubbles: true, cancelable: true }))
  }, text)
}
