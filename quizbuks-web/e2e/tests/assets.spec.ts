import { signInAdmin } from './admin'
import { POLICY, PNG, sponsor, upload } from './content'
import { approvedSponsor } from './publishing'
import { expect, expectAccessible, prepareQuizbuks, rows, run, signIn, test, unique } from './support'

/**
 * The Sponsor's asset library (F-C1, docs/quizbuks/plans/impl/F-C1.md F-C1c): a quiz's cover saved to the library,
 * chosen for another quiz, the asset changed: where it is used then says the copy is older content, and the copy
 * itself is unchanged. Runs against the packaged application only (as every spec here).
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('a Sponsor saves a cover to the library, chooses it for another quiz and sees it as older content once changed',
  async ({ page, request }) => {
    test.setTimeout(180_000)
    const { email, token } = await sponsor(request, 'Asa')
    const cover = await upload(request, token, POLICY.image, 'image.png', 'image/png', PNG)
    const first = await run(request, token, 'QB_QUIZ_SAVE', { title: unique('Coffee '), intro: null, cover,
      timeLimitSec: 300 })
    const second = await run(request, token, 'QB_QUIZ_SAVE', { title: unique('Tea '), intro: null, cover: null,
      timeLimitSec: 300 })
    const name = unique('Brand cover ')

    await signIn(page, '/sponsor/', email)
    await expect(page).not.toHaveURL(/\/login/)
    // Saved to the library from the first quiz's cover.
    await page.goto(`/sponsor/quizzes/${first.quizId}`)
    await page.getByTestId('heading-cover-library-store').click()
    await page.getByTestId('asset-store-name').fill(name)
    await expectAccessible(page, 'sponsor save to the library')
    await page.getByTestId('asset-store-save').click()
    await expect(page.getByTestId('asset-store-dialog')).toHaveCount(0)

    // Chosen for the second quiz's cover and saved: the use is recorded.
    await page.goto(`/sponsor/quizzes/${second.quizId}`)
    await page.getByTestId('heading-cover-library-pick').click()
    await expect(page.getByTestId('asset-picker')).toBeVisible()
    await expectAccessible(page, 'sponsor asset picker')
    await page.getByTestId('asset-picker-search').fill(name)
    await page.keyboard.press('ArrowDown')
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('heading-cover-current')).toBeVisible()
    await page.getByTestId('heading-save').click()
    await expect(page.getByTestId('heading-saved')).toBeVisible()

    // The library: the asset, changed; where it is used now says older content.
    await page.getByRole('link', { name: 'Asset library' }).click()
    const row = page.getByTestId('asset-row').filter({ hasText: name })
    await expect(row).toBeVisible()
    await expectAccessible(page, 'sponsor asset library')
    await row.getByTestId('asset-edit').click()
    await page.getByTestId('asset-description').fill('The square one')
    await page.getByTestId('asset-save').click()
    await expect(page.getByTestId('asset-dialog')).toHaveCount(0)
    // The success notices (chosen, saved) sit at the bottom right, where the row is now that the usage card is above
    // the list; a pointer resting on them keeps them open. Move away and let them close before clicking.
    await page.mouse.move(0, 0)
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0, { timeout: 15_000 })
    await row.getByTestId('asset-uses').click()
    const use = page.getByTestId('asset-use')
    await expect(use).toHaveCount(1)
    await expect(use.getByTestId('asset-use-stale')).toBeVisible()
    await expectAccessible(page, 'sponsor asset uses')
  })

/**
 * Q110 §7 and the spec's F-C1 operations (F-C1d): what two quizzes already have is collected once (the same cover
 * file and the same introduction are listed once each, and nothing is listed again once collected); the collected
 * image is chosen for a question; an introduction is saved to the library and the quiz saved with it; the asset
 * changed, the quiz keeps its copy and "where used" says older content. Support then sees the Sponsor's library, read
 * only, on the Sponsor's page of the management system.
 */
test('a Sponsor collects their content once, uses it, and support sees the library read only',
  async ({ page, context, request }) => {
    test.setTimeout(240_000)
    const { email, token } = await approvedSponsor(request, 'Ivo')
    const cover = await upload(request, token, POLICY.image, 'image.png', 'image/png', PNG)
    const sameIntro = unique('We roast **beans** ')
    const first = await run(request, token, 'QB_QUIZ_SAVE', { title: unique('Roast '), intro: sameIntro, cover,
      timeLimitSec: 300 })
    await run(request, token, 'QB_QUIZ_SAVE', { title: unique('Brew '), intro: sameIntro, cover, timeLimitSec: 300 })
    await run(request, token, 'QB_QUESTION_SAVE', { quizId: first.quizId, stem: 'Which is a bean?', points: 1,
      options: [{ text: 'Arabica', correct: true }, { text: 'Granite', correct: false }] })

    await signIn(page, '/sponsor/', email)
    await expect(page).not.toHaveURL(/\/login/)

    // Collected: the shared cover and the shared introduction once each.
    await page.goto('/sponsor/assets')
    await expect(page.getByTestId('assets-page')).toBeVisible()
    await page.getByTestId('asset-collect').click()
    const collect = page.getByTestId('asset-collect-dialog')
    await expect(collect.getByTestId('asset-candidate')).toHaveCount(2)
    await expectAccessible(page, 'sponsor collect from my content')
    await collect.getByTestId('asset-collect-all').click()
    await collect.getByTestId('asset-collect-run').click()
    await expect(collect.getByTestId('asset-collect-done')).toContainText('2')
    await page.keyboard.press('Escape')
    await expect(collect).toHaveCount(0)
    await expect(page.getByTestId('asset-row')).toHaveCount(2)
    // Collecting again lists nothing: everything is in the library.
    await page.getByTestId('asset-collect').click()
    await expect(page.getByTestId('asset-collect-none')).toBeVisible()
    await page.keyboard.press('Escape')

    const library = await rows(request, token, 'urn:jabiz:dataset:me:QbAsset', [])
    const imageRow = library.find((row) => row.attributes.kind === 'IMAGE')!
    const image = imageRow.attributes
    expect(image.image).toBe(cover)
    const sponsorId = String(image.sponsorId)

    // The collected image chosen for the question's image in the quiz editor, and saved.
    await page.goto(`/sponsor/quizzes/${first.quizId}`)
    await page.getByTestId('tab-questions').click()
    await page.getByTestId('question-edit').first().click()
    const question = page.getByTestId('question-dialog')
    await question.getByTestId('question-image-library-pick').click()
    await expect(page.getByTestId('asset-picker')).toHaveAttribute('data-target', 'QUESTION_IMAGE')
    await page.getByTestId('asset-picker-search').fill(String(image.name))
    await page.keyboard.press('ArrowDown')
    await page.keyboard.press('Enter')
    await expect(question.getByTestId('question-image-current')).toBeVisible()
    await question.getByTestId('question-save').click()
    await expect(question).toHaveCount(0)

    // A new introduction saved to the library from the editor, then the quiz saved with it.
    const intro = unique('Fresh every **morning** ')
    const introName = unique('Morning intro ')
    await page.getByTestId('tab-quiz').click()
    await page.getByTestId('heading-intro').fill(intro)
    await page.getByTestId('heading-intro-library-store').click()
    await page.getByTestId('asset-store-name').fill(introName)
    await page.getByTestId('asset-store-save').click()
    await expect(page.getByTestId('asset-store-dialog')).toHaveCount(0)
    await page.getByTestId('heading-save').click()
    await expect(page.getByTestId('heading-saved')).toBeVisible()

    // The introduction asset changed: the quiz keeps its copy, and where it is used says older content.
    await page.goto('/sponsor/assets')
    const introRow = page.getByTestId('asset-row').filter({ hasText: introName })
    await introRow.getByTestId('asset-edit').click()
    await page.getByTestId('asset-body').fill(unique('Fresh every **evening** '))
    await page.getByTestId('asset-save').click()
    await expect(page.getByTestId('asset-dialog')).toHaveCount(0)
    await introRow.getByTestId('asset-uses').click()
    await expect(page.getByTestId('asset-use')).toHaveCount(1)
    await expect(page.getByTestId('asset-use-stale')).toBeVisible()
    await page.keyboard.press('Escape')
    // Read by its key: the quiz dataset is not filtered by key (as transfer.spec.ts).
    const quiz = await request.get(`/api/datasets/${encodeURIComponent('urn:jabiz:dataset:sponsor:QbQuiz')}`
      + `/entities/${first.quizId}`, { headers: { Authorization: `Bearer ${token}` } })
    expect(quiz.status(), await quiz.text()).toBe(200)
    expect(((await quiz.json()) as { attributes: Record<string, unknown> }).attributes.intro).toBe(intro)
    // The image is used by the question, up to date.
    // By its id: the collected image and introduction both take the quiz's title as their name.
    await page.locator(`[data-testid="asset-row"][data-id="${imageRow.id}"]`).getByTestId('asset-uses').click()
    await expect(page.getByTestId('asset-use')).toHaveCount(1)
    await expect(page.getByTestId('asset-use-stale')).toHaveCount(0)
    await page.keyboard.press('Escape')

    // Support: the Sponsor's page in the management system lists the library read only.
    const admin = await context.newPage()
    await signInAdmin(admin)
    await admin.goto(`/admin/qb/reviews/sponsors/${sponsorId}`)
    const section = admin.getByTestId('sponsor-assets')
    await expect(section.getByTestId('sponsor-asset-row')).toHaveCount(3)
    await expect(section.getByRole('button', { name: /edit|delete|collect|save/i })).toHaveCount(0)
    const supportRow = section.getByTestId('sponsor-asset-row').filter({ hasText: introName })
    await expect(supportRow.getByTestId('sponsor-asset-use-count')).toHaveText('Places: 1')
    await expectAccessible(admin, 'admin sponsor asset library')
    await supportRow.getByTestId('sponsor-asset-uses-open').click()
    const uses = admin.getByTestId('sponsor-asset-uses')
    await expect(uses.getByTestId('sponsor-asset-use')).toHaveCount(1)
    await expect(uses.getByTestId('sponsor-asset-use-stale')).toBeVisible()
    await expect(uses.getByTestId('asset-use-link')).toHaveAttribute('href', `/admin/qb/quizzes/${first.quizId}`)
    await expectAccessible(admin, 'admin sponsor asset uses')
  })
