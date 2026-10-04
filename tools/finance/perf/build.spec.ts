import { expect, test } from '@playwright/test'
import {
  ACCOUNTANT, ADMIN, AP_CLERK, CLERK, CONTROLLER, find, preparePayables, prepareReceivables, run, steppedUp, token,
  type Treasurer,
} from '../../../finance-web/e2e/books'
import { clone, lastSeq, ok, pad, pool, psql, rootsSince, sleep } from './db'

/**
 * The performance data of FIN-NF-001 (docs/finance/perf.md §8), on a running finance application with a database of its
 * own (PGDATABASE, its name with "perf"): the books and master data through the API — 600 accounts, 5,000 customers,
 * 2,000 vendors —, then, for each month of PERF_YEAR (2025), units of real documents made through the API (an invoice
 * paid by a receipt, an invoice left open, a bill paid by a released check run, a bill left open) and copied in the
 * database by clone.sql to a year's volume: 300,000 invoices and 200,000 bills, most of them settled, the open ones
 * mostly of the last two months, customers and vendors taking turns; 500 assets from one acquisition; January to
 * November closed. PERF_SCALE (1) scales the documents' copies (0.001 for a quick try). The amounts are the units'.
 * Made again on the same database, it adds what is missing (master data, assets, closes) and a year of documents more
 * (PERF_DOCUMENTS=false: none).
 */
const year = Number(process.env.PERF_YEAR ?? 2025)
const scale = Number(process.env.PERF_SCALE ?? 1)
const day = (month: number) => `${year}-${pad(month, 2)}-01`

test('FIN-NF-001: a year of performance data', async ({ request }) => {
  test.setTimeout(24 * 60 * 60_000)
  expect(process.env.PGDATABASE ?? '', 'a performance database of its own (PGDATABASE with "perf")').toContain('perf')
  await prepareReceivables(request)
  const treasurer: Treasurer = await preparePayables(request)
  let admin = await token(request, ADMIN)
  const created = await run(request, admin, 'FIN_FISCAL_YEAR_CREATE', { fiscalYear: year, adjustmentPeriod: true })
  expect([200, 422], JSON.stringify(created.body)).toContain(created.status)
  // The books' cutover, the day before the year: where a bank account's reconciliation starts (load/batch.spec.ts).
  if (psql(`SELECT count(*) FROM fi_journal_version WHERE source = 'OPENING'`) === '0') {
    await ok(request, admin, 'FIN_OPENING_POST', { postingDate: `${year - 1}-12-31`, description: 'Opening balances',
      lines: [{ accountCode: '1010', debit: '500000.00' }, { accountCode: '2100', credit: '500000.00' }] })
  }

  // Master data: 600 accounts, 5,000 customers, 2,000 vendors (made only when missing).
  const accounts = Number(psql(`SELECT count(DISTINCT account_id) FROM ledger_account_version`))
  const types = [['ASSET', 'DEBIT', '1'], ['LIABILITY', 'CREDIT', '2'], ['EXPENSE', 'DEBIT', '6'], ['REVENUE', 'CREDIT', '4']]
  await pool(Array.from({ length: Math.max(0, 600 - accounts) }, (_, i) => i), 8, async (i) => {
    const [type, balance, digit] = types[i % types.length]
    const code = `${digit}${pad(800 + Math.floor(i / types.length), 3)}`
    const answer = await run(request, admin, 'FIN_ACCOUNT_CREATE', { accountCode: code, accountName: `Account ${code}`,
      financialType: type, normalBalance: balance, statementLine: `Line ${digit}` })
    expect([200, 422]).toContain(answer.status)
  })
  const address = { street: '1 Test Way', city: 'Austin', state: 'TX', postalCode: '78701', country: 'United States' }
  const customers = Array.from({ length: 5000 }, (_, i) => `PC${pad(i + 1, 5)}`)
  const vendors = Array.from({ length: 2000 }, (_, i) => `PV${pad(i + 1, 5)}`)
  const haveCustomers = new Set(psql(`SELECT DISTINCT customer_code FROM fi_customer_version WHERE customer_code LIKE 'P%'`)
    .split('\n'))
  await pool(['PT-AR-PAID', 'PT-AR-OPEN', ...customers].filter((c) => !haveCustomers.has(c)), 8, async (code) => {
    await ok(request, admin, 'FIN_CUSTOMER_SAVE', { customerCode: code, legalName: `Customer ${code}`, currency: 'USD',
      termsDays: 30, taxCode: 'NT', billing: address, shipping: address })
  })
  const haveVendors = new Set(psql(`SELECT DISTINCT vendor_code FROM fi_vendor_version WHERE vendor_code LIKE 'P%'`)
    .split('\n'))
  await pool(['PT-AP-PAID', 'PT-AP-OPEN', ...vendors].filter((v) => !haveVendors.has(v)), 8, async (code) => {
    await ok(request, admin, 'FIN_VENDOR_SAVE', { vendorCode: code, legalName: `Vendor ${code}`, currency: 'USD',
      termsDays: 30, expenseAccount: '6400', paymentMethod: 'CHECK', entityType: 'C_CORPORATION', remit: address })
  })
  console.log('master data ready')

  // Signed in again each month: a month's copies take longer than a token lasts.
  let clerk = '', apClerk = '', controller = ''
  const invoice = async (customer: string, month: number) => {
    const saved = await ok(request, clerk, 'FIN_INVOICE_SAVE', { customerCode: customer, invoiceDate: day(month),
      lines: [1, 2, 3].map((i) => ({ description: `Item ${i}`, quantity: String(i), unitPrice: `${100 * i}.00`,
        revenueAccount: '4100', taxCode: 'NT' })) })
    await ok(request, clerk, 'FIN_INVOICE_POST', { invoiceId: saved.invoiceId })
    return saved.invoiceId as string
  }
  const bill = async (vendor: string, month: number, no: string) => {
    const saved = await ok(request, apClerk, 'FIN_BILL_SAVE', { vendorCode: vendor, vendorInvoiceNo: no,
      invoiceDate: day(month), lines: [{ description: 'Services', amount: '800.00', account: '6400' },
        { description: 'Supplies', amount: '450.00', account: '6500' }] })
    await ok(request, apClerk, 'FIN_BILL_POST', { billId: saved.billId })
  }
  const settle = async (runId: string, status: string) => {
    for (let i = 0; i < 120; i++) {
      if (psql(`SELECT status FROM fi_payment_run_version WHERE run_id = '${runId}'
        ORDER BY version_no DESC LIMIT 1`) === status) return
      await sleep(500)
    }
    throw new Error(`payment run ${runId} not ${status}`)
  }

  const invoicesPerMonth = Math.round(300_000 / 12 * scale)
  const billsPerMonth = Math.round(200_000 / 12 * scale)
  const openShare = (month: number) => (month >= 11 ? 0.32 : 0.012)
  const subst = (template: string, list: string[]) => ({ [template]: list })
  for (let month = 1; month <= (process.env.PERF_DOCUMENTS === 'false' ? 0 : 12); month++) {
    console.log(`${year}-${pad(month, 2)}`)
    ;[clerk, apClerk, controller] = await Promise.all([CLERK, AP_CLERK, CONTROLLER]
      .map((user) => token(request, user)))
    // An invoice paid in full by a receipt the same day.
    let before = lastSeq()
    const paidId = await invoice('PT-AR-PAID', month)
    await ok(request, clerk, 'FIN_RECEIPT_RECORD', { customerCode: 'PT-AR-PAID', receiptDate: day(month),
      amount: '1400.00', method: 'ACH', bankAccount: '1010', applications: [{ invoiceId: paidId, amount: '1400.00' }] })
    const openInvoices = Math.round(invoicesPerMonth * openShare(month))
    clone(rootsSince(before), invoicesPerMonth - openInvoices - 1, `ar-paid-${month}`, subst('PT-AR-PAID', customers))
    // An invoice left open.
    before = lastSeq()
    await invoice('PT-AR-OPEN', month)
    clone(rootsSince(before), openInvoices - 1, `ar-open-${month}`, subst('PT-AR-OPEN', customers))

    // A bill paid by a check run the same day: proposed, approved by the controller, released by the treasurer.
    before = lastSeq()
    await bill('PT-AP-PAID', month, `PT-${month}`)
    const proposed = await ok(request, apClerk, 'FIN_PAYMENT_RUN_PROPOSE', { paymentDate: day(month), method: 'CHECK',
      dueThrough: `${year}-12-31`, vendorCodes: ['PT-AP-PAID'], description: `Run ${month}` })
    const submitted = await ok(request, apClerk, 'FIN_PAYMENT_RUN_SUBMIT', { runId: proposed.runId })
    await ok(request, controller, 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId, decision: 'APPROVE' })
    await settle(proposed.runId, 'APPROVED')
    await ok(request, await steppedUp(request, treasurer), 'FIN_PAYMENT_RUN_RELEASE', { runId: proposed.runId })
    await settle(proposed.runId, 'RELEASED')
    const openBills = Math.round(billsPerMonth * openShare(month))
    clone(rootsSince(before), billsPerMonth - openBills - 1, `ap-paid-${month}`, subst('PT-AP-PAID', vendors))
    // A bill left open.
    before = lastSeq()
    await bill('PT-AP-OPEN', month, `PT-OPEN-${month}`)
    clone(rootsSince(before), openBills - 1, `ap-open-${month}`, subst('PT-AP-OPEN', vendors))
    // A month of copies at once outruns autovacuum: the planner would choose by the tables' sizes of months before.
    psql('ANALYZE')
  }
  // 500 assets from one acquisition in January (the depreciation run of NF-002 is of 500). Signed in again: the
  // documents take longer than a token lasts.
  admin = await token(request, ADMIN)
  const controllerNow = await token(request, CONTROLLER)
  if (Number(psql(`SELECT count(DISTINCT asset_id) FROM fi_asset_version`)) < 500) {
    await run(request, admin, 'FIN_ACCOUNT_CREATE', { accountCode: '1530', accountName: 'Office Equipment',
      financialType: 'ASSET', normalBalance: 'DEBIT', statementLine: 'Property and equipment, net', controlClass: 'FA_COST' })
    await run(request, admin, 'FIN_ACCOUNT_CREATE', { accountCode: '1590', accountName: 'Accumulated Depreciation',
      financialType: 'ASSET', normalBalance: 'CREDIT', statementLine: 'Property and equipment, net', controlClass: 'FA_ACCUM' })
    await run(request, admin, 'FIN_ACCOUNT_CREATE', { accountCode: '6700', accountName: 'Depreciation Expense',
      financialType: 'EXPENSE', normalBalance: 'DEBIT', statementLine: 'Operating expenses' })
    await ok(request, controllerNow, 'FIN_FA_CLASS_SAVE', { classCode: 'PERF', className: 'Office equipment',
      costAccount: '1530', accumulatedAccount: '1590', expenseAccount: '6700', method: 'SL', lifeMonths: 60,
      convention: 'FULL_MONTH', threshold: '1000.00' })
    const before = lastSeq()
    await ok(request, controllerNow, 'FIN_FA_ACQUIRE', { classCode: 'PERF', description: 'Workstation',
      cost: '3600.00', inServiceDate: `${year}-01-05`, offsetAccount: '1010' })
    clone(rootsSince(before), 499, 'asset', {})
  }

  // January to November closed, as they would be by December: the month's trial balance then reads the snapshots.
  // Each month's depreciation is run before its close (timed: NF-002's 500 assets); the checks this data has nothing
  // for (revaluation, bank reconciliation) are not on the checklist.
  for (const code of ['REVALUATION_RUN', 'BANK_RECONCILED']) {
    const [item] = await find(request, admin, 'urn:jabiz:dataset:default:FinCloseTemplate', 'taskCode', code)
    if (item && item.attributes.active !== false) {
      await ok(request, controllerNow, 'FIN_CLOSE_TEMPLATE_SAVE', { ...item.attributes, active: false })
    }
  }
  for (let month = 1; month <= 11; month++) {
    const periodKey = `${year}-${pad(month, 2)}`
    if (psql(`SELECT status FROM fi_period_version WHERE period_key = '${periodKey}' ORDER BY version_no DESC LIMIT 1`)
      === 'CLOSED') continue
    const [accountant, controller] = await Promise.all([token(request, ACCOUNTANT), token(request, CONTROLLER)])
    const depreciating = Date.now()
    const depreciation = await ok(request, accountant, 'FIN_FA_DEPRECIATION_RUN', { periodKey })
    console.log(`  ${periodKey} depreciation of ${depreciation.assetCount} assets in `
      + `${((Date.now() - depreciating) / 1000).toFixed(1)} s`)
    const started = Date.now()
    const checklist = await ok(request, controller, 'FIN_CLOSE_START', { periodKey })
    for (const task of checklist.tasks as { taskId: string; kind: string; status: string; taskCode: string }[]) {
      if (task.kind === 'MANUAL' && task.status === 'OPEN') {
        await ok(request, task.taskCode === 'REVIEW' ? controller : accountant, 'FIN_CLOSE_TASK_COMPLETE',
          { taskId: task.taskId })
      }
    }
    await ok(request, controller, 'FIN_PERIOD_CLOSE', { periodKey })
    console.log(`  ${periodKey} closed in ${((Date.now() - started) / 1000).toFixed(1)} s`)
  }

  psql('ANALYZE')
  console.log(psql(`SELECT 'invoices ' || count(DISTINCT invoice_id) FROM fi_invoice_version`) + ', '
    + psql(`SELECT 'bills ' || count(DISTINCT bill_id) FROM fi_bill_version`) + ', '
    + psql(`SELECT 'ledger lines ' || count(*) FROM ledger_entry_version`))
})
