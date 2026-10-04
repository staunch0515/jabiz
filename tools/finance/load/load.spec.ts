import { writeFileSync } from 'node:fs'
import { expect, request as playwright, test, type APIRequestContext } from '@playwright/test'
import { ACCOUNTANT, CLERK, CONTROLLER, prepareReceivables, type User } from '../../../finance-web/e2e/books'

/**
 * FIN-NF-002 (ROADMAP F11c; docs/finance/perf.md §8): LOAD_USERS people (50) work on the performance data
 * (tools/finance/perf/build.sh) for LOAD_MINUTES (10), each doing one thing after another with a pause of one to
 * three seconds, as their role allows: accountants post journal entries of 50 lines and run reports, receivables
 * clerks post invoices of 50 lines, open invoices and run the receivables aging, controllers open invoices and run
 * reports (an account's activity, the month's trial balance, the balance sheet, the income statement, an aging). Every request is timed as the client sees it;
 * the p95 of each operation is compared with its target. The report (JSON and Markdown) is written next to the test's
 * results and to LOAD_REPORT when set. Postings fall on LOAD_DATE (2025-12-15: the build leaves December open);
 * reports read the months of PERF_YEAR (2025).
 */
const users = Number(process.env.LOAD_USERS ?? 50)
const minutes = Number(process.env.LOAD_MINUTES ?? 10)
const year = Number(process.env.PERF_YEAR ?? 2025)
const postingDate = process.env.LOAD_DATE ?? `${year}-12-15`
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:8080'
const LINES = 50

/** NF-002's targets, the p95 in milliseconds. */
const TARGETS: Record<string, number> = {
  'invoice.post': 1_000,
  'journal.post': 1_000,
  'document.open': 1_000,
  'account.inquiry': 2_000,
  'trial_balance': 5_000,
  'balance_sheet': 5_000,
  'income_statement': 5_000,
  'aging': 10_000,
}

interface Sample {
  op: string
  ms: number
  ok: boolean
}

const samples: Sample[] = []
const errors: string[] = []
const invoices: string[] = []
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))
const pick = <T>(items: readonly T[]): T => items[Math.floor(Math.random() * items.length)]

function monthEnd(month: number): string {
  return new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10)
}

/** Times one operation: its samples count whether or not it succeeds; a failure is kept to be reported. */
async function timed<T>(op: string, work: () => Promise<T>): Promise<T | undefined> {
  const start = performance.now()
  try {
    const result = await work()
    samples.push({ op, ms: performance.now() - start, ok: true })
    return result
  } catch (e) {
    samples.push({ op, ms: performance.now() - start, ok: false })
    if (errors.length < 50) errors.push(`${op}: ${(e as Error).message.slice(0, 300)}`)
    return undefined
  }
}

/** One person's session through the API. */
class Session {
  private bearer = ''
  constructor(private readonly api: APIRequestContext, private readonly user: User) {}

  async signIn() {
    const response = await this.api.post('/api/auth/login', { data: this.user })
    expect(response.status(), await response.text()).toBe(200)
    this.bearer = (await response.json()).accessToken
  }

  private headers(extra: Record<string, string> = {}) {
    return { Authorization: `Bearer ${this.bearer}`, ...extra }
  }

  private async answer(response: Awaited<ReturnType<APIRequestContext['get']>>, what: string) {
    if (response.status() === 401) {
      await this.signIn()
      throw new Error(`${what}: 401 (signed in again)`)
    }
    if (response.status() !== 200) throw new Error(`${what}: ${response.status()} ${(await response.text()).slice(0, 200)}`)
    return response.json()
  }

  async process(name: string, input: unknown) {
    const response = await this.api.post(`/api/processes/${name}/latest`, {
      headers: this.headers({ 'Idempotency-Key': crypto.randomUUID() }), data: input, timeout: 120_000 })
    return (await this.answer(response, name)).output
  }

  async query(id: string, params: Record<string, unknown>, limit = 500) {
    const response = await this.api.post(`/api/queries/${id}`, { headers: this.headers(), data: { params, limit },
      timeout: 120_000 })
    return (await this.answer(response, id)).items as Record<string, unknown>[]
  }

  async entity(dataset: string, id: string) {
    const response = await this.api.get(`/api/datasets/${encodeURIComponent(dataset)}/entities/${id}`,
      { headers: this.headers(), timeout: 120_000 })
    return this.answer(response, dataset)
  }

  async rows(dataset: string, field: string, value: unknown) {
    const response = await this.api.post(`/api/datasets/${encodeURIComponent(dataset)}/query`, {
      headers: this.headers(), data: { filters: [{ field, op: 'eq', value }], offset: 0, limit: 500 }, timeout: 120_000 })
    return (await this.answer(response, dataset)).items as unknown[]
  }
}

const DATASET = (entity: string) => `urn:jabiz:dataset:default:${entity}`

async function postInvoice(s: Session, worker: number) {
  const lines = Array.from({ length: LINES }, (_, i) => ({ description: `LOAD w${worker} line ${i + 1}`,
    quantity: '1', unitPrice: (10 + i).toFixed(2), revenueAccount: '4100', taxCode: 'NT' }))
  const saved = await timed('invoice.save', () => s.process('FIN_INVOICE_SAVE', { customerCode: 'PT-AR-OPEN',
    invoiceDate: postingDate, lines }))
  if (!saved) return
  const posted = await timed('invoice.post', () => s.process('FIN_INVOICE_POST', { invoiceId: saved.invoiceId }))
  if (posted) invoices.push(saved.invoiceId)
}

async function postJournal(s: Session, worker: number) {
  const lines = Array.from({ length: LINES }, (_, i) => {
    const amount = (100 + i).toFixed(2)
    return i % 2 === 0 ? { accountCode: '6500', debit: amount, credit: null, memo: `LOAD w${worker}` }
      : { accountCode: '2100', debit: null, credit: (100 + i - 1).toFixed(2), memo: `LOAD w${worker}` }
  })
  const saved = await timed('journal.save', () => s.process('FIN_JOURNAL_SAVE', { postingDate,
    description: `LOAD w${worker}`, lines }))
  if (saved) await timed('journal.post', () => s.process('FIN_JOURNAL_SUBMIT', { journalId: saved.journalId }))
}

/** As the invoice page does: the invoice and, side by side, its lines, taxes and applications. */
async function openInvoice(s: Session) {
  if (invoices.length === 0) return
  const id = pick(invoices)
  await timed('document.open', () => Promise.all([
    s.entity(DATASET('FinInvoice'), id),
    s.rows(DATASET('FinInvoiceLine'), 'invoiceId', id),
    s.rows(DATASET('FinInvoiceTax'), 'invoiceId', id),
    s.rows(DATASET('FinApplication'), 'invoiceId', id),
  ]))
}

async function report(s: Session) {
  const month = 1 + Math.floor(Math.random() * 12)
  const through = monthEnd(month)
  const from = `${year}-${String(month).padStart(2, '0')}-01`
  switch (pick(['inquiry', 'inquiry', 'tb', 'bs', 'is', 'ar', 'ap'] as const)) {
    case 'inquiry':
      return timed('account.inquiry', () => s.query('finance.gl.account_inquiry',
        { account: pick(['1010', '1200', '2000', '4100', '6500']), from, to: through }))
    case 'tb':
      return timed('trial_balance', () => s.query('finance.report.trial_balance', { through }))
    case 'bs':
      return timed('balance_sheet', () => s.query('finance.report.balance_sheet', { asOf: through }))
    case 'is':
      return timed('income_statement', () => s.query('finance.report.income_statement', { through }))
    case 'ar':
      return timed('aging', () => s.query('finance.ar.aging', { agingDate: through }))
    case 'ap':
      return timed('aging', () => s.query('finance.ap.aging', { agingDate: through }))
  }
}

type Role = 'accountant' | 'clerk' | 'controller'

/** What each kind of person does, as their roles allow: a clerk does not read the ledger. */
async function step(s: Session, role: Role, w: number) {
  const roll = Math.random()
  switch (role) {
    case 'accountant':
      return roll < 0.5 ? postJournal(s, w) : report(s)
    case 'clerk':
      return roll < 0.5 ? postInvoice(s, w) : roll < 0.85 ? openInvoice(s)
        : timed('aging', () => s.query('finance.ar.aging', { agingDate: monthEnd(1 + Math.floor(Math.random() * 12)) }))
    case 'controller':
      return roll < 0.3 ? openInvoice(s) : report(s)
  }
}

async function worker(s: Session, role: Role, w: number, until: number) {
  // Staggered start, so that the first requests do not all arrive at once.
  await sleep(Math.random() * 3_000)
  while (Date.now() < until) {
    await step(s, role, w)
    await sleep(1_000 + Math.random() * 2_000)
  }
}

function percentile(values: number[], p: number): number {
  const sorted = [...values].sort((a, b) => a - b)
  return sorted.length === 0 ? 0 : sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)]
}

test('FIN-NF-002: response times under 50 users', async ({ request }) => {
  await prepareReceivables(request)
  const contexts = await Promise.all(Array.from({ length: users }, () => playwright.newContext({ baseURL })))
  const roles: Role[] = contexts.map((_, w) => (['accountant', 'clerk', 'controller'] as const)[w % 3])
  const people = { accountant: ACCOUNTANT, clerk: CLERK, controller: CONTROLLER }
  const sessions = contexts.map((c, w) => new Session(c, people[roles[w]]))
  for (const s of sessions) await s.signIn()
  // A few invoices to open before the run has posted any.
  const clerk = sessions[1]
  for (let i = 0; i < 3; i++) await postInvoice(clerk, -1)
  samples.length = 0

  const start = Date.now()
  await Promise.all(sessions.map((s, w) => worker(s, roles[w], w, start + minutes * 60_000)))
  const seconds = (Date.now() - start) / 1000

  const ops = [...new Set(samples.map((x) => x.op))].sort()
  const rows = ops.map((op) => {
    const all = samples.filter((x) => x.op === op)
    const ms = all.filter((x) => x.ok).map((x) => x.ms)
    return { op, count: all.length, failed: all.length - ms.length, p50: Math.round(percentile(ms, 50)),
      p95: Math.round(percentile(ms, 95)), max: Math.round(Math.max(0, ...ms)), target: TARGETS[op] ?? null }
  })
  const markdown = [`${users} users, ${minutes} minutes (${Math.round(seconds)} s), ${samples.length} operations`, '',
    '| operation | count | failed | p50 ms | p95 ms | max ms | target p95 ms | |', '|---|---:|---:|---:|---:|---:|---:|---|',
    ...rows.map((r) => `| ${r.op} | ${r.count} | ${r.failed} | ${r.p50} | ${r.p95} | ${r.max} | ${r.target ?? ''} | `
      + `${r.target === null ? '' : r.p95 <= r.target && r.failed === 0 ? 'ok' : 'NOT MET'} |`)].join('\n')
  const json = JSON.stringify({ users, minutes, seconds, rows, errors }, null, 2)
  console.log(markdown)
  if (errors.length) console.log(errors.join('\n'))
  writeFileSync(test.info().outputPath('load.md'), markdown + '\n')
  writeFileSync(test.info().outputPath('load.json'), json + '\n')
  if (process.env.LOAD_REPORT) writeFileSync(process.env.LOAD_REPORT, json + '\n')
  await Promise.all(contexts.map((c) => c.dispose()))

  for (const r of rows.filter((r) => r.target !== null)) {
    expect(r.failed, `${r.op} failures`).toBe(0)
    expect(r.p95, `${r.op} p95`).toBeLessThanOrEqual(r.target!)
  }
})
