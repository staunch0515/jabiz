import { signInAdmin } from './admin'
import { contentToken, faqInMaterials, helpOn } from './help'
import { nextCode, operatorMemberFor } from './operators'
import { approvedSponsor } from './publishing'
import {
  expect,
  expectAccessible,
  PASSWORD,
  prepareQuizbuks,
  run,
  setAppearance,
  signIn,
  test,
  tokenOf,
  verifiedUser,
} from './support'

/**
 * The AI help (F-C10c, docs/quizbuks/plans/impl/F-C10.md) with the provider answered by the local stub: a Taker asks at
 * the help page a question the FAQ answers and gets the answer with a link to the app's page; a Sponsor asks in the
 * header's panel, rates the answer as not solved and hands the conversation to a person; a member of the Sponsor's
 * operator replies in the Operator Management System; the Sponsor finds the reply in the inbox and opens the
 * conversation. Headquarters' help pages pass axe too. Every page in the light and the dark appearance.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
  await helpOn(request)
})

test('a Taker asks the AI a question the FAQ answers and follows the link in the answer', async ({ page, request }) => {
  test.setTimeout(240_000)
  const content = await contentToken(request)
  const question = await faqInMaterials(request, content.token)
  const email = await verifiedUser(request, 'app', 'Kai')
  await run(request, await tokenOf(request, email, PASSWORD, 'app'), 'QB_PROFILE_SAVE', { displayName: 'Kai',
    countryCode: 'JP', preferredCurrency: 'JPY' })
  await signIn(page, '/taker/', email)
  await expect(page.getByTestId('home')).toBeVisible()
  await page.goto('/taker/help')
  const ask = page.getByTestId('help-ask')
  // The first question is preceded by the privacy notice, once.
  await expect(ask.getByTestId('help-ai-notice')).toBeVisible()
  await expectAccessible(page, 'app help, first-use notice')
  await ask.getByTestId('help-ai-notice-ok').click()
  await ask.getByTestId('help-ai-text').fill(question)
  await ask.getByTestId('help-ai-submit').click()
  const answer = ask.locator('[data-testid="help-ai-message"][data-kind="ANSWER"]')
  await expect(answer).toBeVisible({ timeout: 60_000 })
  await expect(answer.getByTestId('help-ai-body')).toContainText('Open My Kudos')
  await expect(ask.getByTestId('help-ai-live')).toContainText('The AI answered')
  const link = answer.getByTestId('help-ai-link')
  await expect(link).toHaveAttribute('href', '/taker/wallet')
  await expectAccessible(page, 'app help, answered')
  await setAppearance(page, 'dark')
  await page.reload()
  await expect(page.locator('[data-testid="help-ai-message"][data-kind="ANSWER"]')).toBeVisible()
  await expectAccessible(page, 'app help, answered (dark)')
  // The conversation is in the address: the link leads within the app, and back finds the conversation again.
  await page.getByTestId('help-ai-link').click()
  await expect(page).toHaveURL(/\/taker\/wallet$/)
  await page.goBack()
  await expect(page.locator('[data-testid="help-ai-message"][data-kind="ANSWER"]')).toBeVisible()

  // Headquarters' pages of the help: the AI page's help usage, the month's gaps, the manuals.
  await page.context().clearCookies()
  await page.evaluate(() => window.sessionStorage.clear())
  await signInAdmin(page, content.email, PASSWORD)
  for (const appearance of ['light', 'dark'] as const) {
    await setAppearance(page, appearance)
    const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)
    await page.goto('/admin/qb/ai')
    await expect(page.getByTestId('help-usage-row').first()).toBeVisible()
    await expectAccessible(page, at('admin AI page with the help usage'))
    await page.goto('/admin/qb/help-gaps')
    await expect(page.getByTestId('qb-help-gaps')).toBeVisible()
    await expect(page.getByTestId('help-gaps').or(page.getByTestId('help-gaps-none'))).toBeVisible()
    await expectAccessible(page, at('admin help gaps'))
    await page.goto('/admin/qb/help-manuals')
    await expect(page.getByTestId('help-manual')).toHaveCount(2)
    await expectAccessible(page, at('admin help manuals'))
  }
})

test('a Sponsor hands a question to a person, the operator replies, and the Sponsor reads it from the inbox',
  async ({ page, request }) => {
    test.setTimeout(300_000)
    const sponsor = await approvedSponsor(request, 'Rio')
    const member = await operatorMemberFor(request, sponsor.token)
    const question = `Can I move my publication to another quiz? ${Date.now()}`
    await signIn(page, '/sponsor/', sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await page.getByTestId('help-open').click()
    const panel = page.getByTestId('help-panel')
    await panel.getByTestId('help-ai-notice-ok').click()
    await panel.getByTestId('help-ai-text').fill(question)
    await panel.getByTestId('help-ai-submit').click()
    // Not in the materials: the AI is not sure, and the Sponsor says it did not help.
    const answer = panel.locator('[data-testid="help-ai-message"][data-kind="ANSWER"]')
    await expect(answer).toBeVisible({ timeout: 60_000 })
    await expectAccessible(page, 'sponsor help panel, answered')
    await answer.getByTestId('help-ai-unsolved-rate').click()
    await panel.getByTestId('help-ai-text').fill('Please tell me how.')
    await panel.getByTestId('help-ai-unsolved-escalate').click()
    await expect(panel.getByTestId('help-ai-escalated')).toBeVisible()
    await expectAccessible(page, 'sponsor help panel, handed over')
    const threadId = await panel.getByTestId('help-ai').getAttribute('data-thread')
    expect(threadId).toBeTruthy()

    // The operator's member signs in at the Operator Management System and replies.
    const operator = await page.context().browser()!.newContext()
    const desk = await operator.newPage()
    await desk.goto('/operator/login')
    await desk.getByPlaceholder('User name', { exact: true }).fill(member.email)
    await desk.getByPlaceholder('Password', { exact: true }).fill(PASSWORD)
    await desk.getByRole('button', { name: 'Sign in', exact: true }).click()
    await desk.locator('input[autocomplete="one-time-code"]').fill(await nextCode(member.secret, member.enrolledAt))
    await desk.locator('input[autocomplete="one-time-code"]').press('Enter')
    await expect(desk).not.toHaveURL(/\/login/)
    for (const appearance of ['light', 'dark'] as const) {
      await setAppearance(desk, appearance)
      const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)
      await desk.goto('/operator/qb/help')
      const row = desk.locator(`[data-testid="help-thread-row"][data-thread="${threadId}"]`)
      await expect(row).toBeVisible()
      await expect(row).toContainText('Rio Co')
      await expectAccessible(desk, at('operator help desk'))
      await row.getByTestId('help-thread-open').click()
      await expect(desk.getByTestId('help-thread')).toBeVisible()
      await expect(desk.getByTestId('help-messages')).toContainText('Please tell me how.')
      await expectAccessible(desk, at('operator help conversation'))
    }
    await desk.getByTestId('help-reply-text').fill('Make a new publication of the other quiz, then end this one.')
    await desk.getByTestId('help-reply-send').click()
    await expect(desk.getByTestId('help-reply-done')).toBeVisible()
    await operator.close()

    // The Sponsor's inbox has the reply (delivered by the event), which opens the conversation in the panel.
    await page.goto('/sponsor/messages')
    await page.getByTestId('messages-tab-inbox').click()
    const reply = page.getByTestId('announcement').filter({ hasText: 'The help desk replied' })
    await expect.poll(async () => {
      if (await reply.count() > 0) return true
      await page.reload()
      await page.getByTestId('messages-tab-inbox').click()
      return false
    }, { timeout: 60_000, intervals: [2_000] }).toBe(true)
    await reply.first().getByTestId('announcement-open').click()
    await reply.first().getByTestId('announcement-help').click()
    const opened = page.getByTestId('help-panel')
    await expect(opened.getByTestId('help-ai')).toHaveAttribute('data-thread', threadId!)
    await expect(opened.locator('[data-testid="help-ai-message"][data-kind="STAFF"]'))
      .toContainText('Make a new publication of the other quiz')
    await expectAccessible(page, 'sponsor help panel, the reply')
    await setAppearance(page, 'dark')
    await page.reload()
    await page.getByTestId('help-open').click()
    await expect(page.getByTestId('help-panel').getByTestId('help-ai-form')).toBeVisible()
    await expectAccessible(page, 'sponsor help panel (dark)')
  })
