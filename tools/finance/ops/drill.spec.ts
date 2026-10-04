import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test, type APIRequestContext } from '@playwright/test'
import { ACCOUNTANT, prepareBooks, run, token, unique } from '../../../finance-web/e2e/books'

/**
 * The steps of the restore drill (drill.sh; FIN-NF-005), one per DRILL_PHASE, with its files in DRILL_DIR:
 * `books` sets the books up and posts journal entries before the base backup; `work` posts more after it and keeps the
 * January 2026 trial balance (the state at the failure point the books are restored to); `late` posts one more after
 * that point, which the restore must not bring back; `check` compares the restored trial balance with the one kept.
 */
const phase = process.env.DRILL_PHASE ?? ''
const dir = process.env.DRILL_DIR ?? '.'
const kept = join(dir, 'trial-balance.json')

async function post(request: APIRequestContext, bearer: string, day: string, amount: string) {
  const description = unique('DRILL')
  const saved = await run(request, bearer, 'FIN_JOURNAL_SAVE', { postingDate: day, description,
    lines: [{ accountCode: '6400', debit: amount, memo: description }, { accountCode: '2100', credit: amount }] })
  expect(saved.status, JSON.stringify(saved.body)).toBe(200)
  const submitted = await run(request, bearer, 'FIN_JOURNAL_SUBMIT', { journalId: saved.body.output.journalId })
  expect(submitted.status, JSON.stringify(submitted.body)).toBe(200)
  expect(submitted.body.output.status).toBe('POSTED')
}

async function trialBalance(request: APIRequestContext, bearer: string) {
  const response = await request.post('/api/queries/finance.gl.trial_balance', {
    headers: { Authorization: `Bearer ${bearer}` }, data: { params: { through: '2026-01-31' }, limit: 5000 },
  })
  expect(response.status(), await response.text()).toBe(200)
  return (await response.json()).items as Record<string, unknown>[]
}

test(`restore drill: ${phase}`, async ({ request }) => {
  expect(['books', 'work', 'late', 'check'], 'DRILL_PHASE').toContain(phase)
  if (phase === 'books') await prepareBooks(request)
  const accountant = await token(request, ACCOUNTANT)
  if (phase === 'books' || phase === 'work') {
    for (let i = 0; i < 5; i++) await post(request, accountant, `2026-01-${10 + i}`, `${100 + i}.25`)
  }
  if (phase === 'work') writeFileSync(kept, JSON.stringify(await trialBalance(request, accountant), null, 2) + '\n')
  if (phase === 'late') {
    await post(request, accountant, '2026-01-20', '777.77')
    expect(await trialBalance(request, accountant), 'the late entry changes the trial balance')
      .not.toEqual(JSON.parse(readFileSync(kept, 'utf8')))
  }
  if (phase === 'check') {
    expect(await trialBalance(request, accountant), 'the restored trial balance is the one at the failure point')
      .toEqual(JSON.parse(readFileSync(kept, 'utf8')))
  }
})
