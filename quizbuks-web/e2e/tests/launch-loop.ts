import type { APIRequestContext, Browser, Page } from '@playwright/test'
import { contentAdmin } from './legal'
import { operatorWithLead, type OperatorLead } from './operators'
import { taker } from './play'
import { allowRewardMethods } from './publishing'
import { CAPITALS } from './tree'
import { fundedSponsor } from './wallet'
import { adminToken, expect, PASSWORD, rows, run, setControlledParam, tokenOf, unique } from './support'

/*
 * What the first launch's closed-loop test (launch-loop.spec, docs/quizbuks/plans/impl/F-F12.md) shares between its
 * steps: the people of the story, the browsers they sit in and the answering of a quiz on the page.
 */

/** Every step has this long (the whole file is about six minutes). */
export const STEP_TIMEOUT_MS = 240_000

/** The platform fee Kudos a lit node of the platform's template grants (step 6b), and the reward a prize quiz pays. */
export const GRANT = 1000

/** A person of the story: the address and a session at the entry of their app. */
export interface Person {
  email: string
  token: string
  userId: string
}

/** The map, the publications and the attempts the steps leave for the ones after them. */
export interface Loop {
  /** An operator with its lead (the operator's own Sponsor, "the official account") and a second member who reviews. */
  operator: OperatorLead
  /** The Sponsor Sora, who looks after her own map, paying with a prize. */
  sora: Person & { company: string }
  /** The Taker Rin, who answers (Japan, verified). */
  rin: Person
  /** The Taker Kai, whom Sora makes her staff member. */
  kai: Person
  /** The name of the certificate the basics node gives. */
  certificate: string
  map?: { treeId: string; name: string; basics: string; advanced: string }
  basics?: { publicationId: string; name: string; link: string; attemptId?: string }
  advanced?: { publicationId: string; name: string; attemptId?: string }
}

async function personOf(request: APIRequestContext, email: string, entry: string): Promise<Person> {
  const token = await tokenOf(request, email, PASSWORD, entry)
  const me = await (await request.get('/api/auth/me', { headers: { Authorization: `Bearer ${token}` } })).json()
  return { email, token, userId: String(me.userId) }
}

/**
 * Step 0: the people of the story (data of this run only: every name is new). Sora is a funded Sponsor moved to the
 * operator (a Sponsor signs up with the default operator) and allowed digital prizes and prizes at her place, by the
 * content administrator; Rin and Kai are Takers in Japan.
 */
export async function loopCast(request: APIRequestContext): Promise<Loop> {
  const operator = await operatorWithLead(request, 'Ope')
  const company = unique('Sora Coffee Co ')
  const funded = await fundedSponsor(request, 1000, 'Sora', company)
  const first = await personOf(request, funded.email, 'sponsor')
  const hq = await tokenOf(request, await contentAdmin(request), PASSWORD, 'admin')
  await run(request, hq, 'QB_SPONSOR_OPERATOR_ASSIGN', { userId: first.userId, operatorId: operator.operatorId,
    reason: 'Closed loop' })
  await allowRewardMethods(request, funded.token, ['PLATFORM', 'DIGITAL_PRIZE', 'OFFLINE_REDEEM'])
  // The session again: the operator's Sponsor is read from the token's account, after the move.
  const sora = { ...await personOf(request, funded.email, 'sponsor'), company }
  const rin = await taker(request, 'Rin')
  const kai = await taker(request, 'Kai')
  return { operator, sora, rin: await personOf(request, rin.email, 'app'), kai: await personOf(request, kai.email, 'app'),
    certificate: unique('Coffee basics certificate ') }
}

/** A browser of its own at the width of a phone (the Taker's app is used there), with the clipboard if asked. */
export async function phone(browser: Browser, baseURL: string | undefined, clipboard = false) {
  const context = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 }, locale: 'en-US',
    timezoneId: 'Asia/Tokyo', permissions: clipboard ? ['clipboard-read', 'clipboard-write'] : [] })
  const page = await context.newPage()
  return { page, close: () => context.close() }
}

/** The key of the one `/result/<attempt>` the page is at. */
export function attemptIn(page: Page): string {
  const match = /\/result\/([^/?]+)/.exec(new URL(page.url()).pathname)
  expect(match, page.url()).not.toBeNull()
  return decodeURIComponent(match![1])
}

/**
 * Chooses an option by its text and waits until the answer is saved (play.spec's way). The text, not the place: the
 * questions of a quiz are shuffled, so the order is each Taker's own (A2-14).
 */
async function choose(page: Page, text: string) {
  const saved = page.waitForResponse((r) => r.url().includes('/api/processes/QB_ATTEMPT_SAVE/') && r.ok())
  await page.getByRole('radio', { name: text }).click()
  await saved
}

/**
 * Answers the quiz on the page in front of it (the `count` questions of {@link CAPITALS}, all right) and submits it;
 * returns the attempt, and the order the questions came in.
 */
export async function answerQuiz(page: Page, count: number): Promise<{ attemptId: string; order: string[] }> {
  const order: string[] = []
  for (let n = 1; n <= count; n++) {
    await expect(page.getByTestId('play-progress')).toHaveText(`Question ${n} of ${count}`)
    const shown = (await page.getByTestId('play-question').textContent()) ?? ''
    const question = CAPITALS.find((candidate) => shown.includes(candidate.stem))
    expect(question, `a known question in "${shown}"`).toBeDefined()
    order.push(question!.stem)
    await choose(page, question!.right)
    if (n < count) {
      const moved = page.waitForResponse((r) => r.url().includes('/api/processes/QB_ATTEMPT_SAVE/') && r.ok())
      await page.getByTestId('play-next').click()
      await moved
    }
  }
  await page.getByTestId('play-submit').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Submit answers' }).click()
  await expect(page).toHaveURL(/\/result\/[^/]+$/)
  await expect(page.getByTestId('result-passed')).toHaveText('Passed')
  return { attemptId: attemptIn(page), order }
}

/** A controlled parameter as it stands now: its value and kind, or none while nobody has set it. */
async function paramNow(request: APIRequestContext, admin: string, key: string) {
  const [found] = await rows(request, admin, 'urn:jabiz:dataset:platform:SysParam',
    [{ field: 'paramKey', op: 'eq', value: key }], 1)
  return found ? { value: String(found.attributes.value), kind: found.attributes.valueKind as Record<string, unknown> }
    : null
}

/**
 * Remembers controlled parameters a step has to change, and returns what puts them back (call it in a `finally`): the
 * value they had, or `absent` where nobody had set them (the platform reads those as closed). The parameters are
 * global, so a step changing them is in a serial describe: no other worker of the shard reads them meanwhile.
 */
export async function rememberParams(request: APIRequestContext, keys: { key: string; absent: string }[]) {
  // One after the other, with one session: the platform limits how many passwords it hashes at once.
  const admin = await adminToken(request)
  const before: { key: string; absent: string; was: Awaited<ReturnType<typeof paramNow>> }[] = []
  for (const { key, absent } of keys) before.push({ key, absent, was: await paramNow(request, admin, key) })
  return async () => {
    for (const { key, absent, was } of before) {
      await setControlledParam(request, key, was?.value ?? absent, was?.kind)
    }
  }
}
