import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'

/**
 * The reports of FIN-EXP-01 … 14 for one month of the books, read through the API and written to REPORTS_OUT; with
 * REPORTS_COMPARE (an earlier snapshot), they must be the same (upgrade-check.sh; FIN-NF-006). The month is
 * REPORTS_FROM … REPORTS_THROUGH (January 2026, the sample company's); the opening trial balance is the day before.
 * Read as REPORTS_USER / REPORTS_PASSWORD (default the bootstrap administrator of E2E_ADMIN_USER / E2E_ADMIN_PASSWORD),
 * who needs the reports' permissions. A report the user may not run, or that fails, is kept with its status, so the
 * comparison sees it too; one the earlier release did not have (404 then) is reported as new.
 */
const from = process.env.REPORTS_FROM ?? '2026-01-01'
const through = process.env.REPORTS_THROUGH ?? '2026-01-31'
const bank = process.env.REPORTS_BANK ?? 'OPERATING'
const user = {
  userName: process.env.REPORTS_USER ?? process.env.E2E_ADMIN_USER ?? 'admin',
  password: process.env.REPORTS_PASSWORD ?? process.env.E2E_ADMIN_PASSWORD ?? '',
}
const dayBefore = new Date(Date.parse(`${from}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10)

const REPORTS: [string, string, Record<string, unknown>][] = [
  ['FIN-EXP-01', 'finance.gl.trial_balance', { through: dayBefore }],
  ['FIN-EXP-02', 'finance.gl.posting_register', { from, to: through }],
  ['FIN-EXP-03', 'finance.gl.trial_balance', { through }],
  ['FIN-EXP-04', 'finance.report.income_statement', { through }],
  ['FIN-EXP-05', 'finance.report.balance_sheet', { asOf: through }],
  ['FIN-EXP-06', 'finance.report.cash_flow', { from, through }],
  ['FIN-EXP-07', 'finance.report.equity', { from, through }],
  ['FIN-EXP-08', 'finance.ar.aging', { agingDate: through }],
  ['FIN-EXP-09', 'finance.ap.aging', { agingDate: through }],
  ['FIN-EXP-10', 'finance.bank.reconciliation', { bankCode: bank, statementDate: through }],
  ['FIN-EXP-11', 'finance.fa.register', { asOf: through }],
  ['FIN-EXP-12', 'finance.fx.gains_losses', { from, to: through }],
  ['FIN-EXP-13', 'finance.tax.sales_tax', { from, to: through }],
  ['FIN-EXP-14', 'finance.ap.form_1099', { taxYear: Number(through.slice(0, 4)) }],
]
const LIMIT = 10_000

test('FIN-EXP reports snapshot', async ({ request }) => {
  const login = await request.post('/api/auth/login', { data: user })
  expect(login.status(), await login.text()).toBe(200)
  const bearer = (await login.json()).accessToken
  const snapshot: Record<string, unknown> = {}
  for (const [name, id, params] of REPORTS) {
    const response = await request.post(`/api/queries/${id}`, {
      headers: { Authorization: `Bearer ${bearer}` }, data: { params, limit: LIMIT }, timeout: 120_000,
    })
    const body = await response.json().catch(() => ({}))
    if (response.status() === 200) {
      expect((body.items as unknown[]).length, `${id}: more than ${LIMIT} rows`).toBeLessThan(LIMIT)
      snapshot[`${name} ${id}`] = { status: 200, items: body.items }
    } else {
      snapshot[`${name} ${id}`] = { status: response.status(), code: body.code ?? null }
    }
    console.log(`${name} ${id}: ${response.status()}${response.status() === 200 ? `, ${body.items.length} rows` : ''}`)
  }
  const out = process.env.REPORTS_OUT ?? test.info().outputPath('reports.json')
  writeFileSync(out, JSON.stringify({ from, through, reports: snapshot }, null, 2) + '\n')
  const compare = process.env.REPORTS_COMPARE
  if (compare) {
    expect(existsSync(compare), compare).toBe(true)
    const earlier = JSON.parse(readFileSync(compare, 'utf8'))
    for (const key of Object.keys(snapshot)) {
      // A report the earlier release did not have yet is new, not changed.
      if (earlier.reports[key]?.status === 404) console.log(`${key}: new in this release`)
      else expect.soft(snapshot[key], `${key} unchanged`).toEqual(earlier.reports[key])
    }
    expect(Object.keys(snapshot)).toEqual(Object.keys(earlier.reports))
  }
})
