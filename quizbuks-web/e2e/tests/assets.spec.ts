import { POLICY, PNG, sponsor, upload } from './content'
import { expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique } from './support'

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
    await row.getByTestId('asset-uses').click()
    const use = page.getByTestId('asset-use')
    await expect(use).toHaveCount(1)
    await expect(use.getByTestId('asset-use-stale')).toBeVisible()
    await expectAccessible(page, 'sponsor asset uses')
  })
