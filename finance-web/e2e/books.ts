import { createHmac } from 'node:crypto'
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
export const CLERK: User = {
  userName: 'e2e-clerk',
  password: process.env.E2E_CLERK_PASSWORD ?? 'e2e-clerk-password-1',
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
  { accountCode: '1590', accountName: 'Accumulated Depreciation', financialType: 'ASSET', normalBalance: 'CREDIT',
    statementLine: 'Property and equipment, net', controlClass: 'FA_ACCUM' },
  { accountCode: '6700', accountName: 'Depreciation Expense', financialType: 'EXPENSE', normalBalance: 'DEBIT',
    statementLine: 'Operating expenses' },
  { accountCode: '7200', accountName: 'Realized Foreign Exchange (Gain) Loss', financialType: 'EXPENSE',
    normalBalance: 'DEBIT', statementLine: 'Other income (expense), net' },
  { accountCode: '7210', accountName: 'Unrealized Foreign Exchange (Gain) Loss', financialType: 'EXPENSE',
    normalBalance: 'DEBIT', statementLine: 'Other income (expense), net' },
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

export async function run(request: APIRequestContext, bearer: string, process: string, input: unknown) {
  const response = await request.post(`/api/processes/${process}/latest`, {
    headers: { Authorization: `Bearer ${bearer}`, 'Idempotency-Key': crypto.randomUUID() },
    data: input,
  })
  return { status: response.status(), body: await response.json().catch(() => ({})) }
}

export async function find(request: APIRequestContext, bearer: string, dataset: string, field: string, value: unknown) {
  const response = await request.post(`/api/datasets/${encodeURIComponent(dataset)}/query`, {
    headers: { Authorization: `Bearer ${bearer}` },
    data: { filters: [{ field, op: 'eq', value }], limit: 50 },
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

/** Which of the accounts {@code codes} exist: asked one by one, as the books may hold more than a page of accounts. */
async function existingAccounts(request: APIRequestContext, admin: string, codes: string[]): Promise<Set<string>> {
  const found = new Set<string>()
  for (const code of codes) {
    const response = await request.post('/api/queries/finance.gl.account_lookup', {
      headers: { Authorization: `Bearer ${admin}` },
      data: { filters: [{ field: 'accountCode', op: 'eq', value: code }], limit: 1 },
    })
    expect(response.status(), await response.text()).toBe(200)
    if (((await response.json()).items as unknown[]).length > 0) found.add(code)
  }
  return found
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

  const existing = await existingAccounts(request, admin, ACCOUNTS.map((a) => a.accountCode))
  for (const account of ACCOUNTS.filter((a) => !existing.has(a.accountCode))) {
    const created = await run(request, admin, 'FIN_ACCOUNT_CREATE', account)
    expect(created.status, JSON.stringify(created.body)).toBe(200)
  }
  prepared = true
}

/** Signs in on the sign-in page, which lands on the extension's home: the journal register. */
export async function signIn(page: Page, user: User) {
  await page.goto('/login')
  await page.getByPlaceholder('User name').fill(user.userName)
  await page.getByPlaceholder('Password').fill(user.password)
  await page.getByRole('button', { name: /Sign\s*in/ }).click()
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

/** What receivables need on top of the books: accounts, the Austin tax code and NT, settings, the company. */
const AR_ACCOUNTS = [
  { accountCode: '1200', accountName: 'Accounts Receivable', financialType: 'ASSET', normalBalance: 'DEBIT',
    statementLine: 'Accounts receivable, net', controlClass: 'AR' },
  { accountCode: '1210', accountName: 'Allowance for Doubtful Accounts', financialType: 'ASSET',
    normalBalance: 'CREDIT', statementLine: 'Accounts receivable, net' },
  { accountCode: '1250', accountName: 'Unapplied Cash', financialType: 'LIABILITY', normalBalance: 'CREDIT',
    statementLine: 'Accrued liabilities', clearing: true },
  { accountCode: '2200', accountName: 'Sales Tax Payable', financialType: 'LIABILITY', normalBalance: 'CREDIT',
    statementLine: 'Sales tax payable' },
  { accountCode: '4000', accountName: 'Product Sales', financialType: 'REVENUE', normalBalance: 'CREDIT',
    statementLine: 'Revenue' },
  { accountCode: '4100', accountName: 'Service Revenue', financialType: 'REVENUE', normalBalance: 'CREDIT',
    statementLine: 'Revenue' },
  { accountCode: '4900', accountName: 'Sales Returns and Allowances', financialType: 'REVENUE',
    normalBalance: 'DEBIT', statementLine: 'Revenue' },
  { accountCode: '4950', accountName: 'Sales Discounts', financialType: 'REVENUE', normalBalance: 'DEBIT',
    statementLine: 'Revenue' },
]

let receivables = false

/**
 * The books for receivables (ROADMAP F3d-2): the clerk, the accounts, TX-AUSTIN (state 6.25% and local 2%) and NT,
 * the receivables settings and the company's profile. Made or set again on every run; harmless when present.
 */
export async function prepareReceivables(request: APIRequestContext) {
  await prepareBooks(request)
  if (receivables) return
  const admin = await token(request, ADMIN)
  await ensureUser(request, admin, CLERK, 'ReceivablesClerk')
  const existing = await existingAccounts(request, admin, AR_ACCOUNTS.map((a) => a.accountCode))
  for (const account of AR_ACCOUNTS.filter((a) => !existing.has(a.accountCode))) {
    const created = await run(request, admin, 'FIN_ACCOUNT_CREATE', account)
    expect(created.status, JSON.stringify(created.body)).toBe(200)
  }
  for (const code of [
    { taxCode: 'TX-AUSTIN', description: 'Texas, City of Austin combined rate', kind: 'TAXABLE', state: 'TX',
      ratesFrom: '2025-01-01', jurisdictions: [
        { jurisdictionCode: 'TX', jurisdictionName: 'TX state', level: 'STATE', state: 'TX', ratePercent: '6.25' },
        { jurisdictionCode: 'TX-AUSTIN-LOCAL', jurisdictionName: 'Austin (local)', level: 'CITY', state: 'TX',
          ratePercent: '2.00' }] },
    { taxCode: 'NT', description: 'Non-taxable service line', kind: 'NON_TAXABLE', reason: 'NON_TAXABLE_SERVICE' },
  ]) {
    const saved = await run(request, admin, 'FIN_TAX_CODE_SAVE', code)
    expect(saved.status, JSON.stringify(saved.body)).toBe(200)
  }
  const settings = await run(request, admin, 'FIN_AR_SETTINGS_SET', { receivableAccount: '1200',
    allowanceAccount: '1210', returnsAccount: '4900', salesTaxAccount: '2200', unappliedCashAccount: '1250',
    discountAccount: '4950' })
  expect(settings.status, JSON.stringify(settings.body)).toBe(200)
  const profile = await run(request, admin, 'FIN_COMPANY_PROFILE_SET', { legalName: 'Northwind Components, Inc.',
    street: '500 Congress Avenue', city: 'Austin', state: 'TX', postalCode: '78701', country: 'United States',
    remittance: 'ACH or wire to Lakeside National Bank.' })
  expect(profile.status, JSON.stringify(profile.body)).toBe(200)
  receivables = true
}

/** A customer of its own, taxed in Austin on net 30 days: a run's documents are only its own. */
export async function newCustomer(request: APIRequestContext): Promise<string> {
  const admin = await token(request, ADMIN)
  const code = `E${Date.now().toString(36).toUpperCase()}${Math.floor(Math.random() * 1296).toString(36).toUpperCase()}`
  const address = { street: '1 Test Way', city: 'Austin', state: 'TX', postalCode: '78701', country: 'United States' }
  const saved = await run(request, admin, 'FIN_CUSTOMER_SAVE', { customerCode: code, legalName: `Customer ${code}`,
    currency: 'USD', termsDays: 30, taxCode: 'TX-AUSTIN', billing: address, shipping: address,
    contactEmail: `${code.toLowerCase()}@customers.example.com` })
  expect(saved.status, JSON.stringify(saved.body)).toBe(200)
  return code
}

/** RFC 6238 with the authenticator defaults (SHA-1, 6 digits, 30 s), as the server checks it. */
export function totp(secret: string, time = Date.now()): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = ''
  for (const char of secret.replace(/=+$/, '')) bits += alphabet.indexOf(char).toString(2).padStart(5, '0')
  const key = Buffer.from(bits.match(/.{8}/g)!.map((byte) => parseInt(byte, 2)))
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(time / 30_000)))
  const hash = createHmac('sha1', key).update(counter).digest()
  const offset = hash[hash.length - 1] & 0x0f
  return ((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, '0')
}

export const AP_CLERK: User = {
  userName: 'e2e-ap-clerk',
  password: process.env.E2E_AP_CLERK_PASSWORD ?? 'e2e-ap-clerk-password-1',
}

/** A treasurer with a second factor of their own: made on every run, as only that run knows the key. */
export interface Treasurer extends User {
  secret: string
  /** When the last code used was of: a code works once, so the next is of a later 30-second step. */
  lastUsed: number
}

/**
 * The treasurer's next code: of the current step, or of the one after the last used when that was the current one
 * (waiting until it is within the server's drift of one step).
 */
export async function nextCode(treasurer: Treasurer): Promise<string> {
  const step = (time: number) => Math.floor(time / 30_000)
  let now = Date.now()
  if (step(now) <= step(treasurer.lastUsed)) {
    const next = (step(treasurer.lastUsed) + 1) * 30_000
    // One step ahead is accepted; wait while it would be more.
    if (next - now > 30_000) await new Promise((resolve) => setTimeout(resolve, next - now - 30_000 + 1_000))
    now = next
  }
  treasurer.lastUsed = now
  return totp(treasurer.secret, now)
}

const AP_ACCOUNTS = [
  { accountCode: '2000', accountName: 'Accounts Payable', financialType: 'LIABILITY', normalBalance: 'CREDIT',
    statementLine: 'Accounts payable', controlClass: 'AP' },
  { accountCode: '2210', accountName: 'Use Tax Payable', financialType: 'LIABILITY', normalBalance: 'CREDIT',
    statementLine: 'Accrued liabilities' },
  { accountCode: '5900', accountName: 'Purchase Discounts', financialType: 'EXPENSE', normalBalance: 'CREDIT',
    statementLine: 'Operating expenses' },
  { accountCode: '1310', accountName: 'Vendor Prepayments', financialType: 'ASSET', normalBalance: 'DEBIT',
    statementLine: 'Prepaid expenses' },
]

let payables: Treasurer | null = null

/**
 * The books for payables (ROADMAP F4e): the payables clerk, the accounts, the rules finance setup proposed (payment
 * runs need another person's approval) published by the controller, the operating bank account with its check stock,
 * the payables settings, and a treasurer who signs in with a code. Harmless when present.
 */
export async function preparePayables(request: APIRequestContext): Promise<Treasurer> {
  await prepareBooks(request)
  if (payables) return payables
  const admin = await token(request, ADMIN)
  await ensureUser(request, admin, AP_CLERK, 'PayablesClerk')
  const existing = await existingAccounts(request, admin, AP_ACCOUNTS.map((a) => a.accountCode))
  for (const account of AP_ACCOUNTS.filter((a) => !existing.has(a.accountCode))) {
    const created = await run(request, admin, 'FIN_ACCOUNT_CREATE', account)
    expect(created.status, JSON.stringify(created.body)).toBe(200)
  }
  // Every rule finance setup proposed and nobody published yet (on this run or an earlier one): the controller
  // publishes it (four eyes). Only finance setup's own, by their reason: other pending changes are not this test's.
  const controller = await token(request, CONTROLLER)
  const pending = await find(request, admin, 'urn:jabiz:dataset:platform:SysControlChange', 'status', 'PROPOSED')
  for (const change of pending.filter((c) => String(c.attributes.reason ?? '').startsWith('Finance setup:'))) {
    const published = await run(request, controller, 'CONTROL_CHANGE_PUBLISH', { changeId: change.id })
    expect([200, 422], JSON.stringify(published.body)).toContain(published.status)
  }

  const treasurer = await newTreasurer(request, admin)
  // Made once: saving it again would set its check stock back.
  if ((await find(request, admin, 'urn:jabiz:dataset:default:FinBankAccount', 'bankCode', 'OPERATING')).length === 0) {
    const bank = await run(request, await steppedUp(request, treasurer), 'FIN_BANK_ACCOUNT_SAVE', {
      bankCode: 'OPERATING', bankName: 'Lakeside National Bank', glAccount: '1010', routingNumber: '111000025',
      companyAccountNumber: '000123456789', achCompanyId: '1234567890', achCompanyName: 'NORTHWIND',
      nextCheckNo: 10001 })
    expect(bank.status, JSON.stringify(bank.body)).toBe(200)
  }
  const settings = await run(request, controller, 'FIN_AP_SETTINGS_SET', { payableAccount: '2000',
    discountAccount: '5900', useTaxAccount: '2210', prepaymentAccount: '1310', defaultBank: 'OPERATING' })
  expect(settings.status, JSON.stringify(settings.body)).toBe(200)
  payables = treasurer
  return treasurer
}

/** A new treasurer, enrolled in two-step verification through the API with a key only this run knows. */
async function newTreasurer(request: APIRequestContext, admin: string): Promise<Treasurer> {
  const user: User = { userName: unique('e2e-treasurer').replace(' ', '-').toLowerCase(),
    password: 'e2e-treasurer-password-1' }
  await ensureUser(request, admin, user, 'Treasurer')
  const bearer = await token(request, user)
  const enroll = await request.post('/api/auth/mfa/enroll', { headers: { Authorization: `Bearer ${bearer}` } })
  expect(enroll.status(), await enroll.text()).toBe(200)
  const secret = (await enroll.json()).secret as string
  const treasurer: Treasurer = { ...user, secret, lastUsed: 0 }
  const confirm = await request.post('/api/auth/mfa/enroll/confirm', {
    headers: { Authorization: `Bearer ${bearer}` },
    data: { code: await nextCode(treasurer) },
  })
  expect(confirm.status(), await confirm.text()).toBe(200)
  return treasurer
}

/** The treasurer's token confirmed by a code: signed in with the password, then the code of the next step. */
export async function steppedUp(request: APIRequestContext, treasurer: Treasurer): Promise<string> {
  const login = await request.post('/api/auth/login', { data: { userName: treasurer.userName,
    password: treasurer.password } })
  expect(login.status(), await login.text()).toBe(200)
  const challenge = (await login.json()).challenge as string
  const verified = await request.post('/api/auth/challenge/verify', {
    data: { challenge, code: await nextCode(treasurer) },
  })
  expect(verified.status(), await verified.text()).toBe(200)
  return (await verified.json()).accessToken
}

/** Signs a user with a second factor in: the password, then a code, landing on the extension's home. */
export async function signInWithCode(page: Page, user: Treasurer) {
  await page.goto('/login')
  await page.getByPlaceholder('User name').fill(user.userName)
  await page.getByPlaceholder('Password').fill(user.password)
  await page.getByRole('button', { name: /Sign\s*in/ }).click()
  await page.getByPlaceholder('Code').fill(await nextCode(user))
  await page.getByRole('button', { name: /Verify/ }).click()
  await page.waitForURL('**/gl/journals')
}

/**
 * A vendor of its own, paid by check on net 30 days, its work going to professional fees, with a W-9 (an EIN of its
 * own: the application's log is searched for the TINs the tests enter, FIN-NF-007).
 */
export async function newVendor(request: APIRequestContext): Promise<string> {
  const admin = await token(request, ADMIN)
  const code = `V${Date.now().toString(36).toUpperCase()}${Math.floor(Math.random() * 1296).toString(36).toUpperCase()}`
  const saved = await run(request, admin, 'FIN_VENDOR_SAVE', { vendorCode: code, legalName: `Vendor ${code}`,
    currency: 'USD', termsDays: 30, expenseAccount: '6400', paymentMethod: 'CHECK', entityType: 'C_CORPORATION',
    remit: { street: '1 Supply Way', city: 'Austin', state: 'TX', postalCode: '78701', country: 'United States' } })
  expect(saved.status, JSON.stringify(saved.body)).toBe(200)
  const digits = (n: number) => Array.from({ length: n }, () => Math.floor(Math.random() * 10)).join('')
  const tax = await run(request, admin, 'FIN_VENDOR_TAX_SAVE', { vendorCode: code, tinType: 'EIN',
    tin: `${10 + Math.floor(Math.random() * 89)}-${digits(7)}`, w9Date: '2026-01-02' })
  expect(tax.status, JSON.stringify(tax.body)).toBe(200)
  return code
}

/**
 * A bank account of its own for one run of the bank tests (ROADMAP F5d), on a cash account of its own: its cutover
 * with nothing outstanding (an opening entry is posted first when the books have none), two transfers with the
 * operating account in January, and its January statement imported with them and a 15.00 service fee. Returns the
 * bank code. The tables only grow: every run makes new ones.
 */
export async function prepareBank(request: APIRequestContext, treasurer: Treasurer): Promise<string> {
  const admin = await token(request, ADMIN)
  const controller = await token(request, CONTROLLER)
  const code = `E2E${Date.now().toString(36).toUpperCase()}${Math.floor(Math.random() * 1296).toString(36).toUpperCase()}`
  await ensureOpening(request, admin)
  const account = await run(request, admin, 'FIN_ACCOUNT_CREATE', { accountCode: `C-${code}`,
    accountName: `Cash ${code}`, financialType: 'ASSET', normalBalance: 'DEBIT',
    statementLine: 'Cash and cash equivalents', controlClass: 'BANK' })
  expect(account.status, JSON.stringify(account.body)).toBe(200)
  const treasury = await steppedUp(request, treasurer)
  const bank = await run(request, treasury, 'FIN_BANK_ACCOUNT_SAVE', { bankCode: code,
    bankName: 'Lakeside National Bank', glAccount: `C-${code}`, routingNumber: '111000025',
    companyAccountNumber: `9${Date.now().toString().slice(-9)}` })
  expect(bank.status, JSON.stringify(bank.body)).toBe(200)
  const cutover = await run(request, admin, 'FIN_BANK_OPENING_ITEMS', { bankCode: code, statementBalance: '0.00',
    items: [] })
  expect(cutover.status, JSON.stringify(cutover.body)).toBe(200)
  for (const [from, to, amount, day] of [['OPERATING', code, '1200.00', '2026-01-10'],
    [code, 'OPERATING', '300.00', '2026-01-20']]) {
    const moved = await run(request, treasury, 'FIN_BANK_TRANSFER_POST', { fromBank: from, toBank: to, amount,
      sentDate: day, receivedDate: day })
    expect(moved.status, JSON.stringify(moved.body)).toBe(200)
  }
  const rule = await run(request, controller, 'FIN_BANK_ENTRY_RULE_SAVE', { ruleCode: 'E2E-FEE',
    keywords: 'service fee', direction: 'PAYMENT', account: '6400', documentPrefix: 'BANK-FEE',
    description: 'Account service fee' })
  expect(rule.status, JSON.stringify(rule.body)).toBe(200)

  const accountant = await token(request, ACCOUNTANT)
  const csv = ['date,bank_reference,description,amount', '2026-01-01,OPENING,OPENING LEDGER BALANCE,0.00',
    `2026-01-10,${code}-1,TRANSFER FROM OPERATING,1200.00`, `2026-01-20,${code}-2,TRANSFER TO OPERATING,-300.00`,
    `2026-01-31,${code}-3,ACCOUNT SERVICE FEE,-15.00`, '2026-01-31,CLOSING,CLOSING LEDGER BALANCE,885.00'].join('\n')
  const uploaded = await request.post('/api/files?policy=fin.bank.statement', {
    headers: { Authorization: `Bearer ${accountant}` },
    multipart: { file: { name: `${code}.csv`, mimeType: 'text/csv', buffer: Buffer.from(csv) } },
  })
  expect(uploaded.status(), await uploaded.text()).toBe(201)
  const imported = await request.post('/api/imports/finance.bank_statement/commit', {
    headers: { Authorization: `Bearer ${accountant}` },
    data: { fileId: (await uploaded.json()).fileId, params: { bankCode: code } },
  })
  expect(imported.status(), await imported.text()).toBe(200)
  return code
}

/** The books' cutover: an opening entry is posted when the books have none. */
async function ensureOpening(request: APIRequestContext, admin: string) {
  if ((await find(request, admin, 'urn:jabiz:dataset:default:FinJournal', 'source', 'OPENING')).length === 0) {
    const opening = await run(request, admin, 'FIN_OPENING_POST', { postingDate: '2025-12-31',
      description: 'Opening balances', lines: [
        { accountCode: '1010', debit: '1000.00' }, { accountCode: '2100', credit: '1000.00' }] })
    expect(opening.status, JSON.stringify(opening.body)).toBe(200)
  }
}

/**
 * An asset of its own for one run of the asset tests (ROADMAP F6c): a class on a cost account of its own,
 * straight-line over 36 months, and an asset of 3,600.00 acquired against the operating account and placed in service
 * in January 2026, after the books' cutover. Runs an earlier, failed run of the tests left posted are taken back first,
 * so the month to run is January again. Returns its description, which no other run uses.
 */
export async function prepareAsset(request: APIRequestContext): Promise<string> {
  await prepareBooks(request)
  const admin = await token(request, ADMIN)
  const controller = await token(request, CONTROLLER)
  await ensureOpening(request, admin)
  await reverseRuns(request)
  const code = `E${Date.now().toString(36).toUpperCase()}${Math.floor(Math.random() * 1296).toString(36).toUpperCase()}`
  const account = await run(request, admin, 'FIN_ACCOUNT_CREATE', { accountCode: `A-${code}`,
    accountName: `Equipment ${code}`, financialType: 'ASSET', normalBalance: 'DEBIT',
    statementLine: 'Property and equipment, net', controlClass: 'FA_COST' })
  expect(account.status, JSON.stringify(account.body)).toBe(200)
  const assetClass = await run(request, controller, 'FIN_FA_CLASS_SAVE', { classCode: code, className: `Equipment ${code}`,
    costAccount: `A-${code}`, accumulatedAccount: '1590', expenseAccount: '6700', method: 'SL', lifeMonths: 36,
    convention: 'FULL_MONTH', threshold: '1000.00' })
  expect(assetClass.status, JSON.stringify(assetClass.body)).toBe(200)
  const description = `Workstation ${code}`
  const acquired = await run(request, controller, 'FIN_FA_ACQUIRE', { classCode: code, description, cost: '3600.00',
    inServiceDate: '2026-01-05', offsetAccount: '1010' })
  expect(acquired.status, JSON.stringify(acquired.body)).toBe(200)
  return description
}

/**
 * Takes back the posted depreciation runs, latest first, so that every run of the asset tests starts from January
 * (the tables only grow; a run is reversed, never deleted).
 */
export async function reverseRuns(request: APIRequestContext) {
  const accountant = await token(request, ACCOUNTANT)
  for (let i = 0; i < 24; i++) {
    const posted = await find(request, accountant, 'urn:jabiz:dataset:default:FinDepreciationRun', 'status', 'POSTED')
    const latest = posted.map((r) => String(r.attributes.periodKey)).sort().pop()
    if (!latest) return
    const reversed = await run(request, accountant, 'FIN_FA_DEPRECIATION_REVERSE', { periodKey: latest,
      reason: 'End-to-end test: back to the first month' })
    expect(reversed.status, JSON.stringify(reversed.body)).toBe(200)
  }
}

/**
 * The foreign currency settings (ROADMAP F7d): the realized and unrealized accounts, set by the controller; setting
 * them again changes nothing. fx.spec.ts revalues November 2026 with no foreign items in these books: a test that
 * brings foreign documents or bank accounts brings their month-end rates and statements too, or the revaluation is
 * refused.
 */
export async function prepareFx(request: APIRequestContext) {
  await prepareBooks(request)
  const set = await run(request, await token(request, CONTROLLER), 'FIN_FX_SETTINGS_SET',
    { realizedAccount: '7200', unrealizedAccount: '7210' })
  expect(set.status, JSON.stringify(set.body)).toBe(200)
}

/**
 * The close tests' year (ROADMAP F8d): fiscal year 2028 with its adjustment period, which no other test posts in; the
 * close test never closes its months, so it runs again on the same database. Retained earnings is set, so a year
 * close is refused for the months still open rather than for the settings.
 */
export async function prepareCloseYear(request: APIRequestContext) {
  await prepareBooks(request)
  const admin = await token(request, ADMIN)
  const year = await run(request, admin, 'FIN_FISCAL_YEAR_CREATE', { fiscalYear: 2028, adjustmentPeriod: true })
  expect([200, 422], JSON.stringify(year.body)).toContain(year.status)
  expect(await find(request, admin, 'urn:jabiz:dataset:default:FinPeriod', 'periodKey', '2028-13')).toHaveLength(1)
  if ((await find(request, admin, 'urn:jabiz:dataset:default:FinCloseSettings', 'settingsKey', 'CLOSE')).length === 0) {
    if (!(await existingAccounts(request, admin, ['3300'])).has('3300')) {
      const created = await run(request, admin, 'FIN_ACCOUNT_CREATE', { accountCode: '3300',
        accountName: 'Retained Earnings', financialType: 'EQUITY', normalBalance: 'CREDIT',
        statementLine: 'Retained earnings' })
      expect(created.status, JSON.stringify(created.body)).toBe(200)
    }
    const set = await run(request, await token(request, CONTROLLER), 'FIN_CLOSE_SETTINGS_SET',
      { retainedEarningsAccount: '3300' })
    expect(set.status, JSON.stringify(set.body)).toBe(200)
  }
}
