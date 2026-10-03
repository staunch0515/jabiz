import { execSync, spawn } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, request as playwright, test, type APIRequestContext } from '@playwright/test'
import { ACCOUNTANT, ADMIN, CLERK, newCustomer, prepareReceivables, type User } from '../../../finance-web/e2e/books'

/**
 * FIN-NF-003 (ROADMAP F11b): NF003_USERS people (20) post through the API for NF003_MINUTES (30) — half of them journal
 * entries, half invoices each paid at once by a receipt applied to it — while the server is killed NF003_KILLS times
 * (2, `kill -9`: NF003_KILL) and started again (NF003_START). Every request carries an idempotency key and is sent again
 * with the same key until it is answered, as a careful client does; a posting answered 200 is acknowledged. Afterwards:
 * debits equal credits in the trial balance; and in the database (invariants.sql, with the PG* variables) every
 * acknowledged posting is there exactly once, none of the run's documents is beyond those acknowledged, no number
 * sequence has a gap, every ledger transaction balances, and the run's invoices and receipts, every invoice paid in
 * full, leave nothing on the receivables control account.
 */
const minutes = Number(process.env.NF003_MINUTES ?? 30)
const users = Number(process.env.NF003_USERS ?? 20)
const kills = Number(process.env.NF003_KILLS ?? 2)
// The bracket keeps the pattern from matching the shell that runs it.
const killCommand = process.env.NF003_KILL ?? "pkill -9 -f 'java .*[f]inance-.*\\.jar'"
const startCommand = process.env.NF003_START
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:8080'
const run = Date.now().toString(36).toUpperCase()

interface Ack {
  kind: 'JE' | 'INV' | 'RCPT'
  id: string
  owner: string
}

/** Today in Chicago, the company's day. */
function today(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Chicago' }).format(new Date())
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** One person's session: signs in again when the server forgets it (a restart may come with a new signing key). */
class Session {
  private bearer: string | null = null
  constructor(private readonly api: APIRequestContext, private readonly user: User) {}

  private async signIn(): Promise<string> {
    const response = await this.api.post('/api/auth/login', { data: this.user })
    if (response.status() !== 200) throw new Error(`sign-in ${response.status()}`)
    return (this.bearer = (await response.json()).accessToken)
  }

  /**
   * Runs a process with the key given, again and again while the server is away, busy (409, at most ten times) or
   * failing (5xx), for at most ten minutes; returns the first answer of another kind.
   */
  async process(name: string, input: unknown, key: string): Promise<{ status: number; body: any }> {
    const deadline = Date.now() + 10 * 60_000
    let conflicts = 0
    for (let attempt = 0; ; attempt++) {
      try {
        const response = await this.api.post(`/api/processes/${name}/latest`, {
          headers: { Authorization: `Bearer ${this.bearer ?? (await this.signIn())}`, 'Idempotency-Key': key },
          data: input,
          timeout: 60_000,
        })
        const status = response.status()
        if (status === 401) {
          this.bearer = null
        } else if ((status !== 409 || ++conflicts > 10) && status < 500) {
          return { status, body: await response.json().catch(() => ({})) }
        }
      } catch {
        // The server is away: the same request, with the same key, once it is back.
        this.bearer = null
      }
      if (Date.now() > deadline) throw new Error(`${name} still unanswered after ten minutes`)
      await sleep(Math.min(5_000, 250 * 2 ** Math.min(attempt, 4)))
    }
  }

  async posted(name: string, input: unknown): Promise<any> {
    const answer = await this.process(name, input, crypto.randomUUID())
    if (answer.status !== 200) throw new Error(`${name} answered ${answer.status}: ${JSON.stringify(answer.body)}`)
    return answer.body.output
  }
}

async function journals(session: Session, worker: number, until: number, acks: Ack[]) {
  for (let i = 0; Date.now() < until; i++) {
    const amount = (100 + (i % 900) + worker / 100).toFixed(2)
    const saved = await session.posted('FIN_JOURNAL_SAVE', { postingDate: today(),
      description: `NF003 ${run} w${worker} #${i}`, lines: [{ accountCode: '6500', debit: amount },
        { accountCode: '2100', credit: amount }] })
    const submitted = await session.posted('FIN_JOURNAL_SUBMIT', { journalId: saved.journalId })
    expect(submitted.status, JSON.stringify(submitted)).toBe('POSTED')
    acks.push({ kind: 'JE', id: saved.journalId, owner: `w${worker}` })
  }
}

async function receivables(session: Session, customer: string, worker: number, until: number, acks: Ack[]) {
  for (let i = 0; Date.now() < until; i++) {
    const amount = (50 + (i % 900) + worker / 100).toFixed(2)
    const saved = await session.posted('FIN_INVOICE_SAVE', { customerCode: customer, invoiceDate: today(),
      lines: [{ description: `NF003 ${run} #${i}`, quantity: '1', unitPrice: amount, revenueAccount: '4100',
        taxCode: 'NT' }] })
    await session.posted('FIN_INVOICE_POST', { invoiceId: saved.invoiceId })
    acks.push({ kind: 'INV', id: saved.invoiceId, owner: customer })
    const receipt = await session.posted('FIN_RECEIPT_RECORD', { customerCode: customer, receiptDate: today(),
      amount, method: 'ACH', reference: `NF003 ${run} #${i}`, bankAccount: '1010',
      applications: [{ invoiceId: saved.invoiceId, amount }] })
    acks.push({ kind: 'RCPT', id: receipt.receiptId, owner: customer })
  }
}

async function healthy(api: APIRequestContext) {
  for (let i = 0; i < 300; i++) {
    try {
      if ((await api.get('/actuator/health', { timeout: 2_000 })).status() === 200) return
    } catch {
      // still starting
    }
    await sleep(1_000)
  }
  throw new Error('the server did not come back within five minutes')
}

async function down(api: APIRequestContext) {
  for (let i = 0; i < 60; i++) {
    try {
      await api.get('/actuator/health', { timeout: 1_000 })
    } catch {
      return
    }
    await sleep(500)
  }
  throw new Error('the server still answers 30 seconds after it was killed')
}

/**
 * Kills the server at even intervals and starts it again; resolves once the last restart is up. A kill that matches
 * nothing (pkill exits 1) or leaves the server answering fails the run: it would test no crash at all.
 */
async function killer(api: APIRequestContext, start: number, until: number): Promise<number> {
  let done = 0
  for (let k = 1; k <= kills; k++) {
    await sleep(Math.max(0, start + ((until - start) * k) / (kills + 1) - Date.now()))
    console.log(`kill ${k} at ${new Date().toISOString()}`)
    execSync(killCommand, { stdio: 'inherit' })
    await down(api)
    spawn('bash', ['-c', startCommand!], { detached: true, stdio: 'ignore' }).unref()
    await healthy(api)
    console.log(`up again at ${new Date().toISOString()}`)
    done++
  }
  return done
}

test('FIN-NF-003: postings stay whole through concurrency and crashes', async ({ request }) => {
  expect(kills === 0 || startCommand, 'NF003_START starts the server again after a kill').toBeTruthy()
  await prepareReceivables(request)
  const customers: string[] = []
  for (let w = 0; w < users; w++) customers.push(w % 2 === 1 ? await newCustomer(request) : '')

  const contexts = await Promise.all(Array.from({ length: users + 1 }, () => playwright.newContext({ baseURL })))
  const acks: Ack[] = []
  const start = Date.now()
  const until = start + minutes * 60_000
  const [killed] = await Promise.all([
    killer(contexts[users], start, until),
    ...customers.map((customer, w) => w % 2 === 0
      ? journals(new Session(contexts[w], ACCOUNTANT), w, until, acks)
      : receivables(new Session(contexts[w], CLERK), customer, w, until, acks)),
  ])
  const count = (kind: Ack['kind']) => acks.filter((a) => a.kind === kind).length
  console.log(`acknowledged: ${count('JE')} entries, ${count('INV')} invoices, ${count('RCPT')} receipts`)
  expect(killed, 'kills').toBe(kills)
  for (const kind of ['JE', 'INV', 'RCPT'] as const) expect(count(kind), `${kind} acknowledged`).toBeGreaterThan(0)

  // Debits equal credits.
  const bearer = (await (await request.post('/api/auth/login', { data: ADMIN })).json()).accessToken
  const query = async (id: string, params: unknown) => {
    const response = await request.post(`/api/queries/${id}`, { headers: { Authorization: `Bearer ${bearer}` },
      data: { params, limit: 5000 } })
    expect(response.status(), await response.text()).toBe(200)
    return (await response.json()).items as Record<string, any>[]
  }
  const balance = (await query('finance.gl.trial_balance', { through: today() })).filter((r) => !r.summary)
  const debit = balance.reduce((sum, r) => sum + Math.round(Number(r.debit) * 100), 0)
  const credit = balance.reduce((sum, r) => sum + Math.round(Number(r.credit) * 100), 0)
  expect(debit, 'debits equal credits').toBe(credit)

  // In the database: exactly once, nothing more, no gaps, every transaction balanced, receivables settled. The
  // acknowledged postings are kept with the results, for the checks to be run again.
  const file = test.info().outputPath('acked.csv')
  // The run's customers, each, so that one with nothing acknowledged is looked at too.
  const owners = customers.filter((c) => c).map((c) => `CUST,,${c}`)
  writeFileSync(file, [...acks.map((a) => `${a.kind},${a.id},${a.owner}`), ...owners].join('\n') + '\n')
  const problems = execSync(`psql -X -At -v ON_ERROR_STOP=1 -v acked='${file}' -v prefix='NF003 ${run} ' -v receivable=1200`
    + ` -f '${join(test.info().project.testDir, 'invariants.sql')}'`, { encoding: 'utf8' })
    .split('\n').filter((line) => line.trim() && !/^(CREATE TABLE|COPY \d+)$/.test(line.trim()))
  expect(problems, 'invariants').toEqual([])
  await Promise.all(contexts.map((c) => c.dispose()))
})
