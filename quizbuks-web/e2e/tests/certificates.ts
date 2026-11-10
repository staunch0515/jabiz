import type { APIRequestContext, Browser } from '@playwright/test'
import { expect, run, runJobNow } from './support'

/*
 * What the certificate tests share (certificates.spec, launch-loop.spec): the certificates a person holds once the
 * lighting's event issued them, and the verify page as anybody sees it.
 */

type Row = Record<string, unknown>

/**
 * The caller's certificates once there is one (one that names `named`, when given): the lighting's event issues it,
 * and the daily job (run now when the event is slow) is what makes sure of it.
 */
export async function certificatesOf(request: APIRequestContext, token: string, process: string,
  named?: string): Promise<Row[]> {
  const list = async () => ((await run(request, token, process, {})).certificates as Row[])
    .filter((row) => named === undefined || JSON.stringify(row).includes(named))
  try {
    await expect.poll(async () => (await list()).length, { message: 'the certificate is issued', timeout: 30_000 })
      .toBeGreaterThan(0)
  } catch {
    await runJobNow(request, 'qb.tree-certificate-retry')
    await expect.poll(async () => (await list()).length, { message: 'the certificate is issued by the job',
      timeout: 30_000 }).toBeGreaterThan(0)
  }
  return list()
}

/** The verify page as anybody sees it, in a browser of its own (no cache, nobody signed in). */
export async function verifyPage(browser: Browser, baseURL: string | undefined, key: string) {
  const context = await browser.newContext({ baseURL })
  const visitor = await context.newPage()
  await visitor.goto(`/taker/verify/${key}`)
  return { visitor, close: () => context.close() }
}

/** The state the verify page of the key shows, as anybody sees it. */
export async function stateOfVerifyPage(browser: Browser, baseURL: string | undefined, key: string) {
  const { visitor, close } = await verifyPage(browser, baseURL, key)
  try {
    await expect(visitor.getByTestId('verify-state').or(visitor.getByText('No such certificate'))).toBeVisible()
    const state = visitor.getByTestId('verify-page')
    return (await state.count()) > 0 ? await state.getAttribute('data-state') : 'NONE'
  } finally {
    await close()
  }
}
