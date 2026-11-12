import { aiReady } from './ai'
import { approvedSponsor } from './publishing'
import { expect, expectAccessible, openArea, prepareQuizbuks, rows, setAppearance, setControlledParam, signIn, test,
  unique } from './support'

/*
 * Drafting a profile and a "get to know us" quiz from the sponsor's website (F-C7, docs/quizbuks/plans/impl/F-C7.md)
 * with the provider answered by the local stub (tools/quizbuks/claude-stub, which never calls the network and reads
 * the host of the address: `notext.` and `robots.` hosts make the failures). A sponsor asks, the draft comes back,
 * the confirmation lists where each field was taken from and what was changed (the e-mail address and phone number the
 * stub puts in the introduction are taken out), the profile is imported (the stub's images are links no public host
 * serves from here, so the sponsor lets them stay), the quiz draft is made and its questions are added only reviewed.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
  await aiReady(request)
  await setControlledParam(request, 'qb.ai.site-profile.enabled', 'true')
})

const PAGES = 'urn:jabiz:dataset:me:QbSponsorPage'

/** A host the stub reads: its first label is the handle of the drafted page. */
const siteOf = (label: string, prefix = 'www') => `https://${prefix}.${label}.example/`

for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`a Sponsor drafts the profile and the quiz from a website, and reviews before anything is saved (${appearance})`,
    async ({ page, request }) => {
      test.setTimeout(240_000)
      await setAppearance(page, appearance)
      const { email, token } = await approvedSponsor(request, 'Ada')
      const label = unique('site').toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 24)
      await signIn(page, '/sponsor/', email)
      await openArea(page, 'hub')
      await page.getByTestId('profile-transfer').click()
      await page.getByTestId('profile-site').click()
      const dialog = page.getByTestId('site-dialog')
      await expect(dialog.getByTestId('site-form')).toBeVisible()
      await expectAccessible(page, at('sponsor site draft dialog'))

      // The declaration is required; so is the AI notice on first use.
      await dialog.getByTestId('site-address').fill(siteOf(label))
      await dialog.getByTestId('site-submit').click()
      await expect(dialog.getByTestId('site-problems')).toContainText('Confirm that the website is yours')
      await dialog.getByTestId('site-rights').click()
      await dialog.getByTestId('ai-notice-agree').click()
      await dialog.getByTestId('site-submit').click()

      // The stub answers within seconds; the page looks again every five.
      await expect(dialog.getByTestId('site-ready')).toBeVisible({ timeout: 90_000 })
      await expectAccessible(page, at('sponsor site draft ready'))
      await dialog.getByTestId('site-review').click()

      // The confirmation: the draft's fields with where they came from, and what was changed, before anything is saved.
      const confirmation = page.getByTestId('profile-import-dialog')
      await expect(confirmation.getByTestId('site-notes')).toBeVisible()
      await expect(confirmation.getByTestId('site-fields')).toContainText(`Stub Company ${label}`)
      const dropped = confirmation.getByTestId('site-dropped')
      await expect(dropped).toContainText('basics.intro')
      await expect(dropped).toContainText('e-mail address, phone number or social account was removed')
      await expect(confirmation.getByTestId('site-fields')).not.toContainText('taro@')
      await expect(confirmation.getByTestId('site-fields')).not.toContainText('03-1234-5678')
      expect((await rows(request, token, PAGES, [])).length).toBe(0)
      await expectAccessible(page, at('sponsor site draft review'))

      // The stub's images are links no public host serves from here: the server says why, the sponsor lets them stay.
      await confirmation.getByTestId('profile-import-confirm').click()
      await expect(confirmation.getByTestId('profile-import-link-0')).toHaveAttribute('data-state', 'failed')
      await confirmation.getByTestId('profile-import-keep-images').click()
      await confirmation.getByTestId('profile-import-confirm').click()
      await expect(confirmation).toBeHidden({ timeout: 30_000 })
      const pages = await rows(request, token, PAGES, [])
      expect(pages.length).toBe(1)
      expect(pages[0].attributes.handle).toBe(label)

      // The draft is kept: the quiz is made from its articles, and its questions wait in the quiz for review.
      await page.getByTestId('profile-transfer').click()
      await page.getByTestId('profile-site').click()
      await page.getByTestId('site-dialog').getByTestId('site-review').click()
      const again = page.getByTestId('profile-import-dialog')
      await expect(again.getByTestId('site-quiz-make')).toBeVisible()
      await again.getByTestId('site-quiz-make').click()
      await page.waitForURL(/\/sponsor\/.*quizzes\/[0-9a-f-]+/)
      await page.getByTestId('tab-questions').click()
      const review = page.getByTestId('ai-review')
      await expect(review).toBeVisible({ timeout: 30_000 })
      const cards = review.getByTestId('ai-candidate')
      expect(await cards.count()).toBeGreaterThan(0)
      // Nothing is added before a question is marked reviewed.
      await expect(review.getByTestId('ai-accept')).toBeDisabled()
      await cards.nth(0).getByTestId('ai-candidate-reviewed').click()
      await review.getByTestId('ai-accept').click()
      await expect(review).toBeHidden()
      await expect(page.getByTestId('tab-questions')).toContainText('1')
      await expectAccessible(page, at('sponsor site quiz, a question added'))

      // One quiz a draft.
      await openArea(page, 'hub')
      await page.getByTestId('profile-transfer').click()
      await page.getByTestId('profile-site').click()
      await page.getByTestId('site-dialog').getByTestId('site-review').click()
      await expect(page.getByTestId('profile-import-dialog').getByTestId('site-quiz-made')).toBeVisible()
      await expect(page.getByTestId('profile-import-dialog').getByTestId('site-quiz-make')).toHaveCount(0)
    })

  test(`a website that cannot be read says why, and the sponsor can try again (${appearance})`, async ({ page, request }) => {
    test.setTimeout(180_000)
    await setAppearance(page, appearance)
    const { email } = await approvedSponsor(request, 'Beth')
    const label = unique('site').toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 24)
    await signIn(page, '/sponsor/', email)
    await openArea(page, 'hub')
    await page.getByTestId('profile-transfer').click()
    await page.getByTestId('profile-site').click()
    const dialog = page.getByTestId('site-dialog')
    await dialog.getByTestId('site-address').fill(siteOf(label, 'notext'))
    await dialog.getByTestId('site-rights').click()
    await dialog.getByTestId('ai-notice-agree').click()
    await dialog.getByTestId('site-submit').click()
    const failed = dialog.getByTestId('site-failed')
    await expect(failed).toBeVisible({ timeout: 90_000 })
    await expect(failed).toHaveAttribute('data-reason', 'QB_AI_SITE_NO_TEXT')
    await expect(failed).toContainText('almost no text')
    await expect(dialog.getByTestId('site-failed-prompt')).toBeVisible()
    // The form is there for another try.
    await expect(dialog.getByTestId('site-form')).toBeVisible()
    await expectAccessible(page, at('sponsor site draft, a website that cannot be read'))
  })
}
