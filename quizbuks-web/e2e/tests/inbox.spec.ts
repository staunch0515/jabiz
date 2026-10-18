import type { APIRequestContext } from '@playwright/test'
import { signInAdmin } from './admin'
import { contentAdmin, legalAdmin } from './legal'
import { rewardingPublication } from './transactions'
import { earnReward, fundedSponsor, takerInJapan } from './wallet'
import { expect, PASSWORD, prepareQuizbuks, run, signIn, test, unique } from './support'

/*
 * The inbox and the announcements (docs/quizbuks/plans/Q7-messages-legal.md, Q7c): a paid reward becomes a message
 * the badge counts; a sponsor's notice reaches the takers of its live publication and counts its readers without
 * naming them, and QuizBuks withdraws it; an announcement written in the administration reaches the app.
 */

test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

type Item = { itemKind: string; itemId: string; unread: boolean; title?: Record<string, string> }

/** The taker's inbox as the app reads it. */
async function inbox(request: APIRequestContext, token: string): Promise<Item[]> {
  const response = await request.post('/api/queries/qb.app.inbox', {
    headers: { Authorization: `Bearer ${token}` },
    data: { params: { lang: 'en' }, limit: 50 },
  })
  expect(response.status(), await response.text()).toBe(200)
  return (await response.json()).items ?? []
}

test('a paid reward is a message in the inbox; the badge counts it until it is read', async ({ page, request }) => {
  test.setTimeout(120_000)
  const taker = await takerInJapan(request, 'Ines')
  await earnReward(request, taker.token, 10)
  // The message is written by the event's consumer, after the attempt's transaction.
  await expect.poll(async () => (await inbox(request, taker.token)).filter((i) => i.itemKind === 'MESSAGE').length,
    { message: 'the reward message arrives', timeout: 30_000 }).toBe(1)

  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, '/', taker.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await expect(page.getByTestId('nav-inbox-badge')).toHaveText('1')
  await expect(page.getByTestId('nav-inbox')).toHaveAccessibleName(/1 unread/)
  await page.getByTestId('nav-inbox').click()
  const row = page.getByTestId('inbox-row').and(page.locator('[data-kind="MESSAGE"]'))
  await expect(row).toHaveAttribute('data-unread', 'true')
  await expect(row).toContainText('10 Kudos')
  await row.getByRole('link').click()
  await expect(page.getByTestId('inbox-item')).toBeVisible()
  await expect(page.getByTestId('inbox-item-go')).toBeVisible()
  await page.getByTestId('inbox-back').click()
  await expect(row).toHaveAttribute('data-unread', 'false')
  await expect(page.getByTestId('nav-inbox-badge')).toBeHidden()
})

test('a sponsor\'s notice reaches its takers and counts readers without naming them; QuizBuks withdraws it',
  async ({ page, browser, request }) => {
    test.setTimeout(180_000)
    const sponsor = await fundedSponsor(request, 1000)
    const taker = await takerInJapan(request, 'Jun')
    const publication = await rewardingPublication(request, sponsor.token, 10, taker.token)
    const title = unique('Last week ')

    // The sponsor writes the notice in the message centre and sends it at once.
    await signIn(page, '/sponsor/', sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await page.goto('/sponsor/messages')
    await page.getByTestId('notice-new').click()
    await page.getByTestId('notice-publication').click()
    await page.getByRole('option', { name: new RegExp(publication.name) }).click()
    await page.getByTestId('notice-title').fill(title)
    await page.getByTestId('notice-body').fill('Only a week left to answer.')
    await page.getByTestId('notice-save').click()
    await expect(page.getByTestId('notice-ready')).toBeVisible()
    await page.getByTestId('notice-send').click()
    await page.getByRole('button', { name: 'Confirm' }).click()
    const notice = page.getByTestId('notice-row').filter({ hasText: title })
    await expect(notice).toHaveAttribute('data-status', 'SENT')
    const broadcastId = (await notice.getAttribute('data-id'))!

    // The taker reads it and follows "View quiz" to the publication.
    const takerContext = await browser.newContext({ viewport: { width: 390, height: 844 } })
    const app = await takerContext.newPage()
    await signIn(app, '/', taker.email)
    await expect(app.getByTestId('home')).toBeVisible()
    await app.getByTestId('nav-inbox').click()
    const row = app.getByTestId('inbox-row').and(app.locator(`[data-id="${broadcastId}"]`))
    await expect(row).toContainText(title)
    await row.getByRole('link').click()
    await expect(app.getByTestId('inbox-item-sender')).toBeVisible()
    await expect(app.getByTestId('inbox-item-plain')).toContainText('Only a week left')
    await app.getByTestId('inbox-item-go').click()
    await expect(app).toHaveURL(new RegExp(`/p/${publication.publicationId}$`))

    // The sponsor sees the day's count only (review M2): nothing yet, live counts would tell who clicked.
    await page.reload()
    await expect(notice.getByTestId('notice-reads')).toContainText('counted tomorrow')

    // The administration sees the exact counts, and the content administrator withdraws it with a reason.
    const adminContext = await browser.newContext({ viewport: { width: 1280, height: 900 } })
    const admin = await adminContext.newPage()
    await signInAdmin(admin)
    await admin.goto('/admin/qb/broadcasts')
    const adminRow = admin.locator(`[data-testid="broadcast-row"][data-id="${broadcastId}"]`)
    await expect(adminRow).toContainText(title)
    await expect(adminRow).toContainText('1 (100 %)')
    const content = await legalAdmin(request)
    await run(request, content.token, 'QB_ADMIN_BROADCAST_WITHDRAW', { broadcastId, reason: 'Misleading.' })
    await admin.reload()
    await expect(adminRow).toHaveAttribute('data-status', 'WITHDRAWN')

    // It leaves the inbox; the sponsor reads why.
    await app.goto('/inbox')
    await expect(app.getByTestId('inbox-list').or(app.getByTestId('inbox-empty'))).toBeVisible()
    await expect(app.locator(`[data-testid="inbox-row"][data-id="${broadcastId}"]`)).toHaveCount(0)
    await page.reload()
    await expect(notice.getByTestId('notice-withdrawn')).toContainText('Withdrawn by QuizBuks: Misleading.')
    await takerContext.close()
    await adminContext.close()
  })

test('a promotion written in the administration reaches the app', async ({ page, request }) => {
  test.setTimeout(120_000)
  const taker = await takerInJapan(request, 'Kei')
  const title = unique('Maintenance ')
  // QuizBuks's writes ask for a verified address, which the first administrator has not: a content administrator.
  const editor = await contentAdmin(request, 'Nora')
  await page.setViewportSize({ width: 1280, height: 900 })
  await signInAdmin(page, editor, PASSWORD)
  await page.goto('/admin/qb/broadcasts')
  await page.getByTestId('broadcast-new').click()
  // A promotion: it reaches only those registered before it was sent, so later tests' new takers never see it.
  await page.getByTestId('broadcast-type').click()
  await page.getByRole('option', { name: 'Promotion' }).click()
  await page.getByTestId('broadcast-save').click()
  await expect(page.getByTestId('broadcast-problems')).toBeVisible()
  await expect(page.getByTestId('broadcast-send')).toBeDisabled()
  await page.getByTestId('broadcast-title-en').fill(title)
  await page.getByTestId('broadcast-body-en').fill('The app pauses on **Sunday** night.')
  await expect(page.getByTestId('broadcast-preview-en').locator('strong')).toHaveText('Sunday')
  await page.getByTestId('broadcast-save').click()
  await expect(page.getByTestId('broadcast-ready')).toBeVisible()
  await page.getByTestId('broadcast-send').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Send' }).click()
  await expect(page.getByTestId('broadcast-editor')).toBeHidden()
  await expect(page.getByTestId('broadcast-row').filter({ hasText: title })).toHaveAttribute('data-status', 'SENT')

  await expect.poll(async () => (await inbox(request, taker.token)).some((i) => i.itemKind === 'BROADCAST'
    && i.unread && i.title?.en === title), { message: 'the announcement is in the inbox', timeout: 10_000 }).toBe(true)
})
