import { execSync } from 'node:child_process'
import { expect, type APIRequestContext } from '@playwright/test'
import { run } from '../../../finance-web/e2e/books'

/** What the performance data builder (build.spec.ts) and the batch timings (../load/batch.spec.ts) share. */
export const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

export function psql(sql: string): string {
  return execSync('psql -X -At -v ON_ERROR_STOP=1', { input: sql, encoding: 'utf8' }).trim()
}

export function lastSeq(): number {
  return Number(psql('SELECT max(process_seq_id) FROM op_process'))
}

/** The top-level processes run since {@code after}: a unit, without the sign-ins. */
export function rootsSince(after: number): number[] {
  return psql(`SELECT process_seq_id FROM op_process WHERE process_seq_id > ${after} AND parent_seq_id IS NULL
    AND process_name NOT LIKE 'SPONSOR%' ORDER BY 1`).split('\n').filter((s) => s).map(Number)
}

export function clone(roots: number[], copies: number, salt: string, subst: Record<string, string[]>) {
  if (copies < 1 || roots.length === 0) return
  const start = Date.now()
  execSync(`psql -X -q -v ON_ERROR_STOP=1 -v roots=${roots.join(',')} -v copies=${copies} -v days=28 -v salt=${salt}`
    + ` -v subst='${JSON.stringify(subst)}' -f '${__dirname}/clone.sql'`, { stdio: 'inherit' })
  console.log(`  ${salt}: ${copies} copies of ${roots.length} processes in ${((Date.now() - start) / 1000).toFixed(0)} s`)
}

/**
 * Runs {@code work} over {@code items}, {@code width} at a time; the first alone, as it may make what the others share
 * (a customer's payment terms).
 */
export async function pool<T>(items: T[], width: number, work: (item: T) => Promise<void>) {
  if (items.length > 0) await work(items[0])
  let next = 1
  await Promise.all(Array.from({ length: width }, async () => {
    while (next < items.length) await work(items[next++])
  }))
}

export async function ok(request: APIRequestContext, bearer: string, process: string, input: unknown) {
  const answer = await run(request, bearer, process, input)
  expect(answer.status, `${process}: ${JSON.stringify(answer.body)}`).toBe(200)
  return answer.body.output
}

export const pad = (n: number, width: number) => String(n).padStart(width, '0')
