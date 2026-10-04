import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type APIRequestContext } from '@playwright/test'
import { totp } from '../../../finance-web/e2e/books'

/**
 * Demonstration data (docs/finance/install.md §6): the sample company Northwind Components with January 2026 as
 * FIN-EXP-02 has it, entered through the API by its people as the integration tests' JanuaryBooks do, January closed,
 * and February with work waiting: a journal entry and a bill for the controller to approve. Runs once, on an
 * installation with empty books; the people's passwords are DEMO_PASSWORD, the payables clerk's and the treasurer's
 * authenticator keys are printed at the end.
 */
const SAMPLE = resolve(__dirname, '../../../docs/finance-requirements/sample-company')
const PAYROLL = resolve(__dirname, '../../../backend/finance/src/test/resources/payroll/provider-2026-01.csv')
const ADMIN = { userName: process.env.E2E_ADMIN_USER ?? 'admin', password: process.env.E2E_ADMIN_PASSWORD ?? '' }
const PASSWORD = process.env.DEMO_PASSWORD ?? ''

const sample = (file: string) => readFileSync(resolve(SAMPLE, file), 'utf8')
const sleep = (ms: number) => new Promise((done) => setTimeout(done, ms))
/** The number of February's journal entry, the last thing the demonstration data makes. */
const DONE = 'JE-0006'

type Row = Record<string, unknown>

interface Person {
  userName: string
  role: string
  /** The authenticator key of a person who signs in with a code. */
  secret?: string
  lastStep?: number
  bearer?: string
  signedInAt?: number
}

const people: Record<string, Person> = {
  controller: { userName: 'controller', role: 'Controller' },
  accountant: { userName: 'accountant', role: 'Accountant' },
  arClerk: { userName: 'ar-clerk', role: 'ReceivablesClerk' },
  apClerk: { userName: 'ap-clerk', role: 'PayablesClerk' },
  treasurer: { userName: 'treasurer', role: 'Treasurer' },
}

test('demonstration data: Northwind Components, January 2026', async ({ request }) => {
  test.setTimeout(60 * 60_000)
  expect(ADMIN.password, 'E2E_ADMIN_PASSWORD: the administrator\'s password').not.toBe('')
  expect(PASSWORD.length, 'DEMO_PASSWORD: the demonstration users\' password, 12 characters or more')
    .toBeGreaterThanOrEqual(12)
  const api = new Api(request)
  const admin = await api.login(ADMIN)
  // February's entry is made last: with it the data is complete, without it any earlier step's data means a run
  // that stopped half way (or books that are not empty), which a new run cannot continue.
  if ((await api.find(admin, 'urn:jabiz:dataset:default:FinJournal', 'journalNo', DONE)).length > 0) {
    console.log('The books already hold the demonstration data: nothing done.')
    return
  }
  const started = (await api.find(admin, 'urn:jabiz:dataset:platform:SecUser', 'userName', people.controller.userName))
    .length + (await api.find(admin, 'urn:jabiz:dataset:default:FinFiscalYear', 'fiscalYear', 2026)).length
  expect(started, 'empty books: an earlier run stopped half way, or the books hold other data; start again from an '
    + 'empty database (docker compose -f deploy/finance/docker-compose.yml down -v, which removes all data)').toBe(0)

  console.log('People and books…')
  await api.people(admin)
  await openBooks(api, admin)
  console.log('Opening balances, customers, vendors, assets…')
  await openingData(api)
  console.log('Receivables…')
  const rcpt3 = await receivables(api)
  console.log('Payables and payment runs…')
  await payables(api)
  console.log('Journals, depreciation, revaluation, payroll…')
  await journals(api)
  console.log('Bank reconciliation and January close…')
  await closeJanuary(api, rcpt3)
  console.log('February: work waiting for approval…')
  await february(api)

  const base = process.env.E2E_BASE_URL ?? 'http://localhost:8080'
  console.log(`\nDone. Sign in at ${base} as one of (password DEMO_PASSWORD):`)
  for (const person of Object.values(people)) {
    console.log(`  ${person.userName.padEnd(11)} ${person.role}${person.secret
      ? `   authenticator: otpauth://totp/Northwind:${person.userName}?secret=${person.secret}&issuer=Northwind` : ''}`)
  }
})

/**
 * The chart and fiscal year 2026 (by the administrator, as there is no controller before finance setup makes the
 * roles), finance setup with its rules published by the controller, the departments, the euro and the profile.
 */
async function openBooks(api: Api, admin: string) {
  const control: Record<string, string> = { 1010: 'BANK', 1050: 'BANK', 1200: 'AR', 2000: 'AP', 1500: 'FA_COST',
    1510: 'FA_COST', 1520: 'FA_COST', 1590: 'FA_ACCUM' }
  for (const row of csv(sample('chart-of-accounts.csv'))) {
    await api.ok(admin, 'FIN_ACCOUNT_CREATE', { accountCode: row.code, accountName: row.name,
      financialType: row.type.toUpperCase(), normalBalance: row.normal_balance === 'D' ? 'DEBIT' : 'CREDIT',
      statementLine: row.statement_line, controlClass: control[row.code] })
  }
  await api.ok(admin, 'FIN_FISCAL_YEAR_CREATE', { fiscalYear: 2026, adjustmentPeriod: true })
  const setup = await api.ok(admin, 'FIN_SETUP', {})
  const controller = await api.as('controller')
  for (const changeId of Object.values(setup.proposedChanges as Record<string, string>)) {
    await api.ok(controller, 'CONTROL_CHANGE_PUBLISH', { changeId })
  }
  for (const code of ['ADMIN', 'SALES']) {
    await api.commit(controller, 'urn:jabiz:dataset:default:FinDepartment',
      { departmentCode: code, departmentName: code, active: true })
  }
  await api.commit(controller, 'urn:jabiz:dataset:default:FinCurrency',
    { currencyCode: 'EUR', currencyName: 'Euro', minorUnits: 2, active: true })
  await api.ok(controller, 'FIN_COMPANY_PROFILE_SET', { legalName: 'Northwind Components, Inc.',
    street: '500 Congress Avenue', city: 'Austin', state: 'TX', postalCode: '78701', country: 'United States',
    phone: '+1 512 555 0100', email: 'billing@northwind.example',
    remittance: 'ACH or wire to Lakeside National Bank.\nPlease quote the invoice number.' })
}

/** FIN-SCN-01: rates, opening balances, tax codes, customers, vendors, open items, assets, the bank and settings. */
async function openingData(api: Api) {
  const controller = await api.as('controller')
  await api.import(controller, 'finance.fx_rates', sample('fx-rates.csv'), {
    mapping: { columns: { rateDate: 'date', rate: 'eur_usd' }, constants: { fromCurrency: 'EUR', toCurrency: 'USD' } } })
  await api.import(controller, 'finance.opening_balances', sample('opening-balances.csv'))
  await api.import(controller, 'finance.tax_codes', sample('tax-codes.csv'), { params: { ratesFrom: '2025-01-01' } })
  await api.import(controller, 'finance.customers', sample('customers.csv'))
  await api.ok(controller, 'FIN_ACCOUNT_CREATE', { accountCode: '1250', accountName: 'Unapplied Cash',
    financialType: 'LIABILITY', normalBalance: 'CREDIT', statementLine: 'Accrued liabilities', clearing: true })
  await api.ok(controller, 'FIN_ACCOUNT_CREATE', { accountCode: '4950', accountName: 'Sales Discounts',
    financialType: 'REVENUE', normalBalance: 'DEBIT', statementLine: 'Revenue' })
  await api.ok(controller, 'FIN_AR_SETTINGS_SET', { receivableAccount: '1200', allowanceAccount: '1210',
    returnsAccount: '4900', salesTaxAccount: '2200', discountAccount: '4950', unappliedCashAccount: '1250',
    lossRateCurrent: '1', lossRate1: '5' })
  await api.import(controller, 'finance.open_receivables', sample('open-receivables.csv'))

  await api.import(await api.as('apClerk'), 'finance.vendors', sample('vendors.csv'))
  for (const [code, name, cost, method, life] of [['MACH', 'Machinery and equipment', '1500', 'SL', 60],
    ['VEH', 'Vehicles', '1510', 'DDB', 84], ['COMP', 'Computer equipment', '1520', 'SL', 36]]) {
    await api.ok(controller, 'FIN_FA_CLASS_SAVE', { classCode: code, className: name, costAccount: cost,
      accumulatedAccount: '1590', expenseAccount: '6700', method, lifeMonths: life, convention: 'FULL_MONTH',
      threshold: '2500.00' })
  }
  for (const [code, name, type, balance] of [['5900', 'Purchase Discounts', 'EXPENSE', 'CREDIT'],
    ['2210', 'Use Tax Payable', 'LIABILITY', 'CREDIT'], ['1310', 'Vendor Prepayments', 'ASSET', 'DEBIT'],
    ['7400', 'Gain or Loss on Disposal of Assets', 'OTHER', 'DEBIT']]) {
    await api.ok(controller, 'FIN_ACCOUNT_CREATE', { accountCode: code, accountName: name, financialType: type,
      normalBalance: balance, statementLine: name })
  }
  await api.ok(await api.as('treasurer'), 'FIN_BANK_ACCOUNT_SAVE', { bankCode: 'OPERATING',
    bankName: 'Lakeside National Bank', glAccount: '1010', routingNumber: '111000025',
    companyAccountNumber: '000123456789', achCompanyId: '1234567890', achCompanyName: 'NORTHWIND', nextCheckNo: 10001 })
  await api.ok(controller, 'FIN_AP_SETTINGS_SET', { payableAccount: '2000', discountAccount: '5900',
    useTaxAccount: '2210', prepaymentAccount: '1310', defaultBank: 'OPERATING' })
  await api.ok(controller, 'FIN_FA_SETTINGS_SET', { gainLossAccount: '7400' })
  await api.ok(controller, 'FIN_FX_SETTINGS_SET', { realizedAccount: '7200', unrealizedAccount: '7210' })
  await api.import(controller, 'finance.open_payables', sample('open-payables.csv'))
  await api.import(controller, 'finance.fixed_assets', sample('fixed-assets.csv'))
  await api.import(controller, 'finance.bank_opening_items',
    'date,reference,description,amount\n2025-12-28,CHK-1045,Check 1045,-3200.00\n',
    { params: { bankCode: 'OPERATING', statementBalance: '253200.00' } })
}

/** FIN-SCN-03: receipts, invoices INV-1004 to INV-1007 and credit memo CM-2001; RCPT-0003 waits unapplied. */
async function receivables(api: Api): Promise<string> {
  const clerk = await api.as('arClerk')
  const line = (description: string, quantity: string, unitPrice: string, revenueAccount?: string, taxCode?: string) =>
    ({ description, quantity, unitPrice, revenueAccount, taxCode })
  const numbers: string[] = []
  const invoice = async (customerCode: string, invoiceDate: string, lines: Row[]) => {
    const { invoiceId } = await api.ok(clerk, 'FIN_INVOICE_SAVE', { customerCode, invoiceDate, lines })
    numbers.push((await api.ok(clerk, 'FIN_INVOICE_POST', { invoiceId })).invoiceNo)
    return invoiceId as string
  }
  await receipt(api, 'C100', '2026-01-05', '32475.00', 'INV-1001')
  const inv1004 = await invoice('C100', '2026-01-06', [line('Components', '100', '400.00', '4000'),
    line('Engineering services', '1', '10000.00', '4100', 'NT')])
  const { invoiceId: cm2001 } = await api.ok(clerk, 'FIN_INVOICE_SAVE', { customerCode: 'C100',
    invoiceDate: '2026-01-10', kind: 'CREDIT_MEMO', originalInvoiceId: inv1004,
    lines: [line('Returned components', '5', '400.00')] })
  expect((await api.ok(await api.as('controller'), 'FIN_INVOICE_POST', { invoiceId: cm2001 })).invoiceNo)
    .toBe('CM-2001')
  await api.ok(clerk, 'FIN_CREDIT_APPLY', { creditMemoId: cm2001, invoiceId: inv1004, amount: '2165.00',
    applicationDate: '2026-01-10' })
  await invoice('C400', '2026-01-12', [line('Components', '1', '50000.00', '4000')])
  await invoice('C200', '2026-01-14', [line('Engineering services', '1', '18000.00', '4100')])
  await invoice('C300', '2026-01-15', [line('Components', '50', '500.00', '4000')])
  await receipt(api, 'C200', '2026-01-16', '24025.00', 'INV-1002')
  const unapplied = await api.ok(clerk, 'FIN_RECEIPT_RECORD', { customerCode: 'C300', receiptDate: '2026-01-25',
    amount: '20000.00', method: 'ACH', bankAccount: '1010' })
  expect(unapplied.receiptNo).toBe('RCPT-0003')
  expect(numbers, 'the invoice numbers start at 1004 (FINANCE_AR_INVOICE_NUMBERS_START)')
    .toEqual(['INV-1004', 'INV-1005', 'INV-1006', 'INV-1007'])
  return unapplied.receiptId as string
}

async function receipt(api: Api, customerCode: string, receiptDate: string, amount: string, invoiceNo: string) {
  const clerk = await api.as('arClerk')
  const found = await api.find(clerk, 'urn:jabiz:dataset:default:FinInvoice', 'invoiceNo', invoiceNo)
  expect(found, invoiceNo).toHaveLength(1)
  const [invoice] = found
  await api.ok(clerk, 'FIN_RECEIPT_RECORD', { customerCode, receiptDate, amount, method: 'ACH', bankAccount: '1010',
    applications: [{ invoiceId: invoice.attributes.invoiceId ?? invoice.id, amount }] })
}

/** FIN-SCN-04 and FIN-SCN-05's payables: vendor bank details, bills and three payment runs released. */
async function payables(api: Api) {
  const clerk = await api.as('apClerk')
  const bankChange = (vendorCode: string, routingNumber: string, bankAccountNumber: string) => ({ vendorCode,
    bankName: 'Some Bank', routingNumber, bankAccountNumber, reason: 'Vendor set-up form' })
  const vendorBanks = 'urn:jabiz:dataset:default:FinVendorBankAccount'
  for (const [vendor, routing, account] of [['V100', '021000021', '100200300'], ['V300', '091000019', '300400500'],
    ['V600', '111000025', '600700800'], ['V800', '021000021', '800900100']]) {
    const { approvalRequestId } = await api.ok(clerk, 'FIN_VENDOR_BANK_CHANGE', bankChange(vendor, routing, account))
    await api.decide(approvalRequestId, vendorBanks, ['vendorCode', vendor], 'status', 'ACTIVE')
  }
  // V200's details wait until after PAY-RUN-01, which therefore holds DC-2025-12.
  const v200 = await api.ok(clerk, 'FIN_VENDOR_BANK_CHANGE', bankChange('V200', '011000015', '200300400'))
  const bills: Record<string, string> = {}
  for (const bill of [['V300', 'MP-2026-01', '2026-01-02', '6200', '8500.00'],
    ['V100', 'P-7902', '2026-01-09', '5000', '22000.00'], ['V800', 'JR-014', '2026-01-21', '6400', '1500.00']]) {
    bills[bill[1]] = await saveBill(api, bill)
  }
  const run01 = await api.ok(clerk, 'FIN_PAYMENT_RUN_PROPOSE', { paymentDate: '2026-01-08', method: 'ACH',
    dueThrough: '2026-01-20', description: 'ACH payment run 01' })
  expect(run01.runNo).toBe('PAY-RUN-01')
  await release(api, run01.runId)
  await api.decide(v200.approvalRequestId, vendorBanks, ['vendorCode', 'V200'], 'status', 'ACTIVE')
  const run02 = await api.ok(clerk, 'FIN_PAYMENT_RUN_PROPOSE', { paymentDate: '2026-01-22', method: 'ACH',
    dueThrough: '2026-01-31', vendorCodes: ['V200', 'V300', 'V800'], description: 'ACH payment run 02' })
  await api.ok(clerk, 'FIN_PAYMENT_RUN_ADD', { runId: run02.runId, billId: bills['MP-2026-01'] })
  await api.ok(clerk, 'FIN_PAYMENT_RUN_ADD', { runId: run02.runId, billId: bills['JR-014'] })
  expect(run02.runNo).toBe('PAY-RUN-02')
  await release(api, run02.runId)
  const tax = await api.ok(clerk, 'FIN_PAYMENT_RUN_PROPOSE', { paymentDate: '2026-01-20', method: 'MANUAL' })
  await api.ok(clerk, 'FIN_PAYMENT_RUN_ADD', { runId: tax.runId, payee: 'Texas Comptroller', account: '2200',
    amount: '3300.00', description: 'Texas sales tax return December 2025' })
  await release(api, tax.runId)
  // The bills the runs do not pay; TS-5520 makes FA-003.
  for (const bill of [['V700', 'TS-5520', '2026-01-15', '1520', '12000.00'],
    ['V200', 'DC-2026-01', '2026-01-20', '6400', '7500.00'], ['V400', 'CS-0126', '2026-01-20', '6500', '1200.00'],
    ['V600', 'CPL-0126', '2026-01-28', '6300', '3600.00']]) {
    await saveBill(api, bill)
  }
  expect(await api.find(await api.as('controller'), 'urn:jabiz:dataset:default:FinAsset', 'assetNo', 'FA-003'),
    'TS-5520 capitalized as FA-003 (FINANCE_FA_ASSET_NUMBERS_START)').toHaveLength(1)
}

/** Saves and posts a bill [vendor, number, date, account, amount]; one above 10,000.00 is approved first. */
async function saveBill(api: Api, [vendorCode, vendorInvoiceNo, invoiceDate, account, amount]: string[],
  approve = true): Promise<string> {
  const clerk = await api.as('apClerk')
  const { billId } = await api.ok(clerk, 'FIN_BILL_SAVE', { vendorCode, vendorInvoiceNo, invoiceDate,
    lines: [{ description: vendorInvoiceNo, amount, account }] })
  const posted = await api.ok(clerk, 'FIN_BILL_POST', { billId })
  if (approve && posted.approval === 'PENDING') {
    await api.decide(posted.approvalRequestId, 'urn:jabiz:dataset:default:FinBill', billId, 'approval', 'APPROVED')
  }
  return posted.billId as string
}

async function release(api: Api, runId: string) {
  const { approvalRequestId } = await api.ok(await api.as('apClerk'), 'FIN_PAYMENT_RUN_SUBMIT', { runId })
  await api.decide(approvalRequestId, 'urn:jabiz:dataset:default:FinPaymentRun', runId, 'status', 'APPROVED')
  await api.ok(await api.as('treasurer'), 'FIN_PAYMENT_RUN_RELEASE', { runId })
}

/** FIN-SCN-02 and FIN-SCN-06 steps 1–3: JE-0001 to JE-0003, depreciation, revaluation, payroll, provisions. */
async function journals(api: Api) {
  const accountant = await api.as('accountant')
  const controller = await api.as('controller')
  const line = (accountCode: string, debit?: string, credit?: string) => ({ accountCode, debit, credit })
  const journal = (postingDate: string, description: string, lines: Row[]) =>
    api.ok(accountant, 'FIN_JOURNAL_SAVE', { postingDate, description, lines })
  const submit = async (journalId: string) => {
    const submitted = await api.ok(accountant, 'FIN_JOURNAL_SUBMIT', { journalId })
    if (submitted.approvalRequestId) {
      await api.decide(submitted.approvalRequestId, 'urn:jabiz:dataset:default:FinJournal', journalId, 'status', 'POSTED')
    } else {
      expect(submitted.status, `journal ${submitted.journalNo}`).toBe('POSTED')
    }
  }
  const je1 = await journal('2026-01-15', 'Payout of 2025 bonus accrued at year end',
    [line('2100', '15000.00'), line('1010', undefined, '15000.00')])
  await api.ok(controller, 'FIN_JOURNAL_GRANT_CONTROL_EXCEPTION', { journalId: je1.journalId,
    reason: 'Bonus paid from the operating account' })
  await submit(je1.journalId)
  const je2 = await journal('2026-01-31', 'Accrue annual audit fee',
    [line('6400', '25000.00'), line('2100', undefined, '25000.00')])
  await submit(je2.journalId)
  const template = await api.commit(accountant, 'urn:jabiz:dataset:default:FinRecurringTemplate', {
    templateCode: 'PREPAID-INS', description: 'Recurring: amortize prepaid insurance 1/12', startDate: '2026-01-01',
    endDate: '2026-12-31', active: true })
  for (const [lineNo, accountCode, side] of [[1, '6600', 'debit'], [2, '1300', 'credit']] as const) {
    await api.commit(accountant, 'urn:jabiz:dataset:default:FinRecurringLine',
      { templateId: template.id, lineNo, accountCode, [side]: '1000.00' })
  }
  await api.ok(accountant, 'FIN_RECURRING_RUN', { date: '2026-01-31' })
  await api.ok(accountant, 'FIN_FA_DEPRECIATION_RUN', { periodKey: '2026-01' })
  await api.ok(accountant, 'FIN_FX_REVALUE', { periodKey: '2026-01' })

  // PAYROLL-2601 from the provider's file (FIN-DI-004).
  for (const [providerCode, accountCode, side] of [['GROSS_WAGES', '6100', 'DEBIT'], ['EMPLOYER_TAX', '6150', 'DEBIT'],
    ['NET_PAY', '1010', 'CREDIT'], ['EMPLOYEE_WITHHOLDING', '2150', 'CREDIT'],
    ['EMPLOYER_TAX_LIABILITY', '2150', 'CREDIT']]) {
    await api.commit(controller, 'urn:jabiz:dataset:default:FinPayrollMapping',
      { providerCode, accountCode, side, active: true })
  }
  await api.import(accountant, 'finance.payroll', readFileSync(PAYROLL, 'utf8'), { params: { run: 'PAYROLL-2601',
    payDate: '2026-01-30', description: 'Payroll summary from external provider' } })
  const [payroll] = await api.find(accountant, 'urn:jabiz:dataset:default:FinJournal', 'journalNo', 'PAYROLL-2601')
  await api.decide(payroll.attributes.approvalRequestId, 'urn:jabiz:dataset:default:FinJournal', payroll.id, 'status',
    'POSTED')

  const je4 = await journal('2026-01-31', 'Estimated federal income tax provision',
    [line('8000', '1362.90'), line('2400', undefined, '1362.90')])
  await submit(je4.journalId)
  // The savings account is no bank account of these books: its interest is an entry the controller allows on cash.
  const interest = await journal('2026-01-31', 'Savings interest', [line('1050', '125.00'), line('7300', undefined, '125.00')])
  await api.ok(controller, 'FIN_JOURNAL_GRANT_CONTROL_EXCEPTION', { journalId: interest.journalId,
    reason: 'Interest credited by the bank on the savings account' })
  await submit(interest.journalId)
}

/**
 * FIN-SCN-05 and FIN-SCN-06 step 4: RCPT-0003 applied, the statement imported and matched, the fee and the interest
 * entered, the reconciliation signed off, the cash flow classes set and January closed by the controller.
 */
async function closeJanuary(api: Api, rcpt3: string) {
  const accountant = await api.as('accountant')
  const controller = await api.as('controller')
  const found = await api.find(accountant, 'urn:jabiz:dataset:default:FinInvoice', 'invoiceNo', 'INV-1003')
  expect(found, 'INV-1003').toHaveLength(1)
  const [inv1003] = found
  await api.ok(await api.as('arClerk'), 'FIN_RECEIPT_APPLY', { receiptId: rcpt3, applicationDate: '2026-01-25',
    applications: [{ invoiceId: inv1003.attributes.invoiceId ?? inv1003.id, amount: '20000.00' }] })

  const fileId = await api.upload(accountant, 'fin.bank.statement', 'bank-statement-2026-01.csv',
    sample('bank-statement-2026-01.csv'))
  await api.post(accountant, '/api/imports/finance.bank_statement/commit', { fileId, params: { bankCode: 'OPERATING' } })
  const { proposals } = await api.ok(accountant, 'FIN_BANK_MATCH_PROPOSE', { bankCode: 'OPERATING' })
  const accepted = await api.ok(accountant, 'FIN_BANK_MATCH_ACCEPT', { bankCode: 'OPERATING',
    proposals: (proposals as Row[]).map((p) => ({ lineId: p.lineId,
      items: (p.items as Row[]).map((i) => ({ kind: i.kind, id: i.id })) })) })
  expect(accepted.matched).toBe(8)
  for (const [ruleCode, keywords, account, documentPrefix, description] of [
    ['FEE', 'service fee', '6800', 'BANK-FEE', 'Account service fee'],
    ['INT', 'interest', '7100', 'BANK-INT', 'Line of credit interest']]) {
    await api.ok(controller, 'FIN_BANK_ENTRY_RULE_SAVE', { ruleCode, keywords, direction: 'PAYMENT', account,
      documentPrefix, description })
  }
  for (const item of await api.query(accountant, 'finance.bank.statement_items', { bankCode: 'OPERATING' })) {
    if (['BNK-0009', 'BNK-0010'].includes(String(item.bankReference))) {
      await api.ok(accountant, 'FIN_BANK_ENTRY_FROM_LINE', { lineId: item.lineId })
    }
  }
  const prepared = await api.ok(accountant, 'FIN_BANK_REC_PREPARE', { bankCode: 'OPERATING', statementDate: '2026-01-31' })
  expect(Number(prepared.difference)).toBe(0)
  const completed = await api.ok(accountant, 'FIN_BANK_REC_COMPLETE', { reconciliationId: prepared.reconciliationId })
  await api.decide(completed.approvalRequestId, 'urn:jabiz:dataset:default:FinBankReconciliation',
    prepared.reconciliationId, 'status', 'SIGNED_OFF')

  // The sample chart has no cash flow classes: the controller gives them, and names the settings' accounts.
  const classes: Record<string, string[]> = { CASH: ['1010', '1050'],
    OPERATING: ['1200', '1210', '1300', '2000', '2100', '2150', '2200', '2400'],
    INVESTING: ['1500', '1510', '1520', '1590'], FINANCING: ['2300', '3000', '3100', '3200'] }
  for (const [cashFlowClass, codes] of Object.entries(classes)) {
    for (const accountCode of codes) await api.ok(controller, 'FIN_ACCOUNT_UPDATE', { accountCode, cashFlowClass })
  }
  await api.ok(controller, 'FIN_REPORT_SETTINGS_SET', { interestAccounts: '7100', incomeTaxAccounts: '8000',
    incomeTaxPayableAccounts: '2400', receivablesAccounts: '1200-1210', accruedAccounts: '2100-2150',
    debtAccounts: '2300' })

  const checklist = await api.ok(accountant, 'FIN_CLOSE_START', { periodKey: '2026-01' })
  for (const task of checklist.tasks as Row[]) {
    if (task.kind === 'MANUAL' && task.status === 'OPEN') {
      await api.ok(task.taskCode === 'REVIEW' ? controller : accountant, 'FIN_CLOSE_TASK_COMPLETE',
        { taskId: task.taskId, note: 'Done for the demonstration' })
    }
  }
  await api.ok(controller, 'FIN_PERIOD_SET_STATE', { periodKey: '2026-01', status: 'SOFT_CLOSED' })
  const closed = await api.ok(controller, 'FIN_PERIOD_CLOSE', { periodKey: '2026-01' })
  expect(closed.status).toBe('CLOSED')

  // The books are the sample's: FIN-EXP-05's total assets and FIN-EXP-04's net income.
  const line = async (template: string, params: Row, code: string, column: string) =>
    Number((await api.query(controller, template, params)).find((l) => l.lineCode === code)?.[column])
  expect(await line('finance.report.balance_sheet', { asOf: '2026-01-31' }, 'TOTAL_ASSETS', 'amount')).toBe(617415)
  expect(await line('finance.report.income_statement', { through: '2026-01-31' }, 'NET_INCOME', 'month')).toBe(5127.1)
}

/** February's waiting work: an accrual submitted and a large bill posted, both waiting for the controller's approval. */
async function february(api: Api) {
  const bill = await saveBill(api, ['V100', 'P-8044', '2026-02-03', '5000', '18400.00'], false)
  const accountant = await api.as('accountant')
  expect((await api.read(accountant, 'urn:jabiz:dataset:default:FinBill', bill))?.approval).toBe('PENDING')
  const { journalId } = await api.ok(accountant, 'FIN_JOURNAL_SAVE', { postingDate: '2026-02-05',
    description: 'Accrue February consulting fees', lines: [{ accountCode: '6400', debit: '12500.00' },
      { accountCode: '2100', credit: '12500.00' }] })
  const submitted = await api.ok(accountant, 'FIN_JOURNAL_SUBMIT', { journalId })
  expect(submitted.approvalRequestId).toBeTruthy()
  expect(submitted.journalNo).toBe(DONE)
}

/** The API as the demonstration's people use it. */
class Api {
  constructor(private readonly request: APIRequestContext) {}

  async login(user: { userName: string; password: string }): Promise<string> {
    const response = await this.request.post('/api/auth/login', { data: user })
    expect(response.status(), await response.text()).toBe(200)
    return (await response.json()).accessToken
  }

  /** The five people with their finance roles; the payables clerk and the treasurer enrolled with a code. */
  async people(admin: string) {
    for (const person of Object.values(people)) {
      if ((await this.find(admin, 'urn:jabiz:dataset:platform:SecUser', 'userName', person.userName)).length === 0) {
        await this.ok(admin, 'SEC_USER_CREATE', { userName: person.userName, password: PASSWORD,
          displayName: person.userName })
      }
    }
  }

  /**
   * A person's token: signed in again after five minutes, with a code when they have one, well within the step-up
   * age (10 minutes by default) that the processes asking for a code accept.
   */
  async as(name: keyof typeof people): Promise<string> {
    const person = people[name]
    if (person.bearer && Date.now() - (person.signedInAt ?? 0) < 5 * 60_000) return person.bearer
    await this.assignRole(person)
    if (['apClerk', 'treasurer'].includes(name) && !person.secret) await this.enroll(person)
    const signedInAt = Date.now()
    const response = await this.request.post('/api/auth/login', { data: { userName: person.userName,
      password: PASSWORD } })
    expect(response.status(), await response.text()).toBe(200)
    const body = await response.json()
    if (body.challenge) {
      const verified = await this.request.post('/api/auth/challenge/verify', {
        data: { challenge: body.challenge, code: await this.code(person) } })
      expect(verified.status(), await verified.text()).toBe(200)
      person.bearer = (await verified.json()).accessToken
    } else {
      person.bearer = body.accessToken
    }
    person.signedInAt = signedInAt
    return person.bearer!
  }

  private assigned = new Set<string>()

  private async assignRole(person: Person) {
    if (this.assigned.has(person.userName)) return
    const admin = await this.login(ADMIN)
    const [user] = await this.find(admin, 'urn:jabiz:dataset:platform:SecUser', 'userName', person.userName)
    const [role] = await this.find(admin, 'urn:jabiz:dataset:platform:SecRole', 'roleCode', person.role)
    expect(role, `role ${person.role} (finance setup makes it)`).toBeTruthy()
    const held = await this.find(admin, 'urn:jabiz:dataset:platform:SecUserRole', 'userId', user.id)
    if (!held.some((r) => r.attributes.roleId === role.id)) {
      await this.commit(admin, 'urn:jabiz:dataset:platform:SecUserRole', { userId: user.id, roleId: role.id })
    }
    this.assigned.add(person.userName)
  }

  private async enroll(person: Person) {
    const bearer = await this.login({ userName: person.userName, password: PASSWORD })
    const enroll = await this.request.post('/api/auth/mfa/enroll', { headers: { Authorization: `Bearer ${bearer}` } })
    expect(enroll.status(), await enroll.text()).toBe(200)
    person.secret = (await enroll.json()).secret
    const confirm = await this.request.post('/api/auth/mfa/enroll/confirm', {
      headers: { Authorization: `Bearer ${bearer}` }, data: { code: await this.code(person) } })
    expect(confirm.status(), await confirm.text()).toBe(200)
  }

  /** A code of a 30-second step not used yet: a code works once. */
  private async code(person: Person): Promise<string> {
    let step = Math.floor(Date.now() / 30_000)
    while (step <= (person.lastStep ?? -1)) {
      await sleep(1_000)
      step = Math.floor(Date.now() / 30_000)
    }
    person.lastStep = step
    return totp(person.secret!, step * 30_000)
  }

  /**
   * The controller approves; waits until the document `id` of `dataset` shows the result (`field`
   * = `value`), which reaches it as an event. A key field as `[field, value]` finds the document instead.
   */
  async decide(requestId: unknown, dataset: string, id: unknown, field: string, value: string) {
    expect(requestId, 'an approval request').toBeTruthy()
    const controller = await this.as('controller')
    await this.ok(controller, 'APPROVAL_DECIDE', { requestId, decision: 'APPROVE' })
    for (let i = 0; i < 120; i++) {
      const rows = Array.isArray(id) ? (await this.find(controller, dataset, id[0], id[1])).map((r) => r.attributes)
        : [await this.read(controller, dataset, id)]
      if (rows.some((r) => r?.[field] === value)) return
      await sleep(500)
    }
    throw new Error(`${dataset} ${id}: the approval did not reach it`)
  }

  async read(bearer: string, dataset: string, id: unknown): Promise<Row | undefined> {
    const response = await this.request.get(`/api/datasets/${encodeURIComponent(dataset)}/entities/${id}`,
      { headers: { Authorization: `Bearer ${bearer}` } })
    return response.status() === 200 ? (await response.json()).attributes : undefined
  }

  async run(bearer: string, process: string, input: unknown) {
    const response = await this.request.post(`/api/processes/${process}/latest`, {
      headers: { Authorization: `Bearer ${bearer}`, 'Idempotency-Key': crypto.randomUUID() }, data: input })
    return { status: response.status(), body: await response.json().catch(() => ({})) }
  }

  async ok(bearer: string, process: string, input: unknown): Promise<Row & Record<string, any>> {
    const answer = await this.run(bearer, process, input)
    expect(answer.status, `${process}: ${JSON.stringify(answer.body)}`).toBe(200)
    return answer.body.output ?? {}
  }

  async post(bearer: string, path: string, data: unknown): Promise<any> {
    const response = await this.request.post(path, { headers: { Authorization: `Bearer ${bearer}` }, data })
    expect(response.status(), `${path}: ${await response.text()}`).toBe(200)
    return response.json()
  }

  async find(bearer: string, dataset: string, field: string, value: unknown): Promise<{ id: string; attributes: Row }[]> {
    const body = await this.post(bearer, `/api/datasets/${encodeURIComponent(dataset)}/query`,
      { filters: [{ field, op: 'eq', value }], limit: 50 })
    return body.items ?? []
  }

  async commit(bearer: string, dataset: string, attributes: Row): Promise<{ id: string } & Row> {
    const body = await this.post(bearer, `/api/datasets/${encodeURIComponent(dataset)}/commit`,
      { changes: [{ action: 'INSERT', attributes }] })
    return body[0]
  }

  async query(bearer: string, template: string, params: Row): Promise<Row[]> {
    return (await this.post(bearer, `/api/queries/${template}`, { params, limit: 500 })).items
  }

  async upload(bearer: string, policy: string, name: string, content: string): Promise<string> {
    const response = await this.request.post(`/api/files?policy=${policy}`, {
      headers: { Authorization: `Bearer ${bearer}` },
      multipart: { file: { name, mimeType: 'text/csv', buffer: Buffer.from(content) } } })
    expect(response.status(), await response.text()).toBe(201)
    return (await response.json()).fileId
  }

  async import(bearer: string, importId: string, content: string, options: { mapping?: Row; params?: Row } = {}) {
    const fileId = await this.upload(bearer, 'fin.import', `${importId}.csv`, content)
    return this.post(bearer, `/api/imports/${importId}/commit`, { fileId, ...options })
  }
}

/** A CSV file (the sample company's: quoted fields hold commas, never quotes) by column name. */
function csv(text: string): Record<string, string>[] {
  const fields = (line: string) => [...line.matchAll(/"([^"]*)"|([^,]*)/g)]
    .filter((m) => m.index === 0 || line[m.index - 1] === ',').map((m) => m[1] ?? m[2])
  const [header, ...lines] = text.split(/\r?\n/).filter((l) => l.trim() !== '')
  const names = fields(header)
  return lines.map((line) => {
    const values = fields(line)
    return Object.fromEntries(names.map((name, i) => [name, values[i] ?? '']))
  })
}
