import { writeFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import {
  ACCOUNTANT, ADMIN, CONTROLLER, find, preparePayables, run, steppedUp, token,
} from '../../../finance-web/e2e/books'
import { ok, pad, pool, psql } from '../perf/db'

/**
 * FIN-NF-002's batch targets (ROADMAP F11c; docs/finance/perf.md §8), on the performance data after the load test
 * (run.sh, then this with batch.sh): December's depreciation of the 500 assets (≤ 1 minute); a statement of
 * BATCH_LINES (5,000) lines matched (proposed and accepted, ≤ 2 minutes) on a bank account of its own, whose December
 * deposits are posted through the API first; December closed and the year closed (≤ 10 minutes). It closes the year
 * PERF_YEAR (2025): the last thing done to the data. The timings are written as for the load test.
 */
const year = Number(process.env.PERF_YEAR ?? 2025)
const lines = Number(process.env.BATCH_LINES ?? 5000)
const BANK = 'PERFBANK'
const GL = '1020'

const TARGETS: Record<string, number> = { depreciation: 60_000, matching: 120_000, 'year-end close': 600_000 }
const timings: Record<string, number> = {}

async function timed<T>(name: string, work: () => Promise<T>): Promise<T> {
  const start = performance.now()
  const result = await work()
  timings[name] = Math.round(performance.now() - start)
  console.log(`${name}: ${(timings[name] / 1000).toFixed(1)} s`)
  return result
}

test('FIN-NF-002: batch timings', async ({ request }) => {
  expect(process.env.PGDATABASE ?? '', 'a performance database of its own (PGDATABASE with "perf")').toContain('perf')
  let admin = await token(request, ADMIN)
  let [accountant, controller] = await Promise.all([ACCOUNTANT, CONTROLLER].map((user) => token(request, user)))
  const december = `${year}-12`

  // December's depreciation, every asset in service.
  const depreciation = await timed('depreciation', () => ok(request, accountant, 'FIN_FA_DEPRECIATION_RUN',
    { periodKey: december }))
  console.log(`  ${depreciation.assetCount} assets, ${depreciation.total}`)

  // A bank account of its own from the books' cutover, its December deposits posted one by one as transfers from the
  // operating account (a bank account takes postings from its subledger only; receipts left unapplied would stop the
  // close), each amount its own.
  const treasurer = await preparePayables(request)
  if (psql(`SELECT count(*) FROM fi_bank_account_version WHERE bank_code = '${BANK}'`) === '0') {
    const account = await run(request, admin, 'FIN_ACCOUNT_CREATE', { accountCode: GL, accountName: 'Cash PERFBANK',
      financialType: 'ASSET', normalBalance: 'DEBIT', statementLine: 'Cash and cash equivalents', controlClass: 'BANK' })
    expect([200, 422], JSON.stringify(account.body)).toContain(account.status)
    await ok(request, await steppedUp(request, treasurer), 'FIN_BANK_ACCOUNT_SAVE', { bankCode: BANK, bankName: 'Lakeside National Bank',
      glAccount: GL, routingNumber: '111000025', companyAccountNumber: '9000000020' })
    await ok(request, admin, 'FIN_BANK_OPENING_ITEMS', { bankCode: BANK, statementBalance: '0.00', items: [] })
  }
  const run_ = Date.now().toString(36).toUpperCase()
  const deposits = Array.from({ length: lines }, (_, i) => ({ i, day: `${december}-${pad(1 + (i % 28), 2)}`,
    amount: (100 + i + (i % 100) / 100).toFixed(2), reference: `PB${run_}-${i + 1}` }))
  const started = Date.now()
  // Stepped up again every thousand transfers: a step-up lasts minutes.
  for (let from = 0; from < deposits.length; from += 1000) {
    const treasury = await steppedUp(request, treasurer)
    await pool(deposits.slice(from, from + 1000), 8, async (t) => {
      await ok(request, treasury, 'FIN_BANK_TRANSFER_POST', { fromBank: 'OPERATING', toBank: BANK, amount: t.amount,
        sentDate: t.day, receivedDate: t.day })
    })
  }
  console.log(`  ${lines} deposits posted in ${((Date.now() - started) / 1000).toFixed(0)} s`)
  // Signed in again: the deposits take longer than a token lasts.
  ;[admin, accountant, controller] = await Promise.all([ADMIN, ACCOUNTANT, CONTROLLER].map((user) => token(request, user)))

  // The bank's statement of them, and matching: the proposals, accepted 500 at a time.
  const opening = psql(`SELECT coalesce(max(closing_balance), 0) FROM fi_bank_statement_version
    WHERE bank_code = '${BANK}'`)
  const total = deposits.reduce((sum, r) => sum + Math.round(Number(r.amount) * 100), 0) / 100
  const csv = ['date,bank_reference,description,amount', `${december}-01,OPENING,OPENING LEDGER BALANCE,${opening}`,
    ...deposits.map((r) => `${r.day},${r.reference},TRANSFER FROM OPERATING ${r.reference},${r.amount}`),
    `${december}-28,CLOSING,CLOSING LEDGER BALANCE,${(Number(opening) + total).toFixed(2)}`].join('\n')
  const uploaded = await request.post('/api/files?policy=fin.bank.statement', {
    headers: { Authorization: `Bearer ${accountant}` },
    multipart: { file: { name: `${BANK}-${run_}.csv`, mimeType: 'text/csv', buffer: Buffer.from(csv) } },
  })
  expect(uploaded.status(), await uploaded.text()).toBe(201)
  await timed('statement import', async () => {
    const imported = await request.post('/api/imports/finance.bank_statement/commit', {
      headers: { Authorization: `Bearer ${accountant}` }, timeout: 600_000,
      data: { fileId: (await uploaded.json()).fileId, params: { bankCode: BANK } },
    })
    expect(imported.status(), await imported.text()).toBe(200)
  })
  let matched = 0
  await timed('matching', async () => {
    const proposed = await timed('matching: propose', () => ok(request, accountant, 'FIN_BANK_MATCH_PROPOSE',
      { bankCode: BANK }))
    const proposals = (proposed.proposals as { lineId: string; items: { kind: string; id: string }[] }[])
      .map((p) => ({ lineId: p.lineId, items: p.items.map((item) => ({ kind: item.kind, id: item.id })) }))
    console.log(`  ${proposals.length} proposals, ${proposed.openLines} lines and ${proposed.openItems} items left`)
    for (let i = 0; i < proposals.length; i += 500) {
      matched += (await ok(request, accountant, 'FIN_BANK_MATCH_ACCEPT',
        { bankCode: BANK, proposals: proposals.slice(i, i + 500) })).matched
    }
  })
  console.log(`  ${matched} lines matched`)

  // December closed, then the year.
  await timed('year-end close', async () => {
    const checklist = await ok(request, controller, 'FIN_CLOSE_START', { periodKey: december })
    for (const task of checklist.tasks as { taskId: string; kind: string; status: string; taskCode: string }[]) {
      if (task.kind === 'MANUAL' && task.status === 'OPEN') {
        await ok(request, task.taskCode === 'REVIEW' ? controller : accountant, 'FIN_CLOSE_TASK_COMPLETE',
          { taskId: task.taskId })
      }
    }
    await timed('year-end close: December', () => ok(request, controller, 'FIN_PERIOD_CLOSE', { periodKey: december }))
    if ((await find(request, admin, 'urn:jabiz:dataset:default:FinCloseSettings', 'settingsKey', 'CLOSE')).length === 0) {
      const equity = await run(request, admin, 'FIN_ACCOUNT_CREATE', { accountCode: '3300',
        accountName: 'Retained Earnings', financialType: 'EQUITY', normalBalance: 'CREDIT',
        statementLine: 'Retained earnings' })
      expect([200, 422], JSON.stringify(equity.body)).toContain(equity.status)
      await ok(request, controller, 'FIN_CLOSE_SETTINGS_SET', { retainedEarningsAccount: '3300' })
    }
    await timed('year-end close: year', () => ok(request, controller, 'FIN_YEAR_CLOSE', { fiscalYear: year }))
  })

  const rows = Object.entries(timings).map(([name, ms]) => ({ name, ms, target: TARGETS[name] ?? null }))
  const markdown = ['| batch | seconds | target seconds | |', '|---|---:|---:|---|', ...rows.map((r) =>
    `| ${r.name} | ${(r.ms / 1000).toFixed(1)} | ${r.target === null ? '' : r.target / 1000} | `
    + `${r.target === null ? '' : r.ms <= r.target ? 'ok' : 'NOT MET'} |`)].join('\n')
  console.log(markdown)
  writeFileSync(test.info().outputPath('batch.md'), markdown + '\n')
  writeFileSync(test.info().outputPath('batch.json'), JSON.stringify({ lines, matched, rows }, null, 2) + '\n')
  if (process.env.BATCH_REPORT) writeFileSync(process.env.BATCH_REPORT, JSON.stringify({ lines, matched, rows }) + '\n')
  expect(matched, 'every line matched').toBe(lines)
  for (const r of rows.filter((r) => r.target !== null)) expect(r.ms, r.name).toBeLessThanOrEqual(r.target!)
})
