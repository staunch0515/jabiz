import { aiReady, quizForAi } from './ai'
import { PDF, POLICY, query, upload } from './content'
import { approvedSponsor } from './publishing'
import { expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique, setAppearance } from './support'

/**
 * AI covers and PDF materials (docs/quizbuks/plans/Q8-ai.md, Q8b; M-25, M-26) with the provider answered by the local
 * stub: an approved sponsor asks for covers on the quiz page (the AI notice on first use), chooses one of the three
 * pictures the server drew, and it becomes the quiz's cover; then asks for questions from the quiz's article and its
 * PDF, which is sent as the file itself. Every step passes axe, light and dark.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
  await aiReady(request)
})

for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`a sponsor chooses an AI cover and asks from a PDF (${appearance})`, async ({ page, request }) => {
    test.setTimeout(240_000)
    await setAppearance(page, appearance)
    const sponsor = await approvedSponsor(request, 'Cora')
    const title = unique('Cover quiz ')
    const quizId = await quizForAi(request, sponsor.token, title)
    const pdf = await upload(request, sponsor.token, POLICY.pdf, 'material.pdf', 'application/pdf', PDF)
    await run(request, sponsor.token, 'QB_MATERIAL_SAVE', { quizId, kind: 'PDF', title: 'The paper',
      description: 'About the Seine', pdf })
    await signIn(page, '/sponsor/', sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await page.goto(`/sponsor/quizzes/${quizId}`)

    // Covers, on the quiz's own tab.
    const panel = page.getByTestId('ai-cover-panel')
    await expect(panel.getByTestId('ai-cover-generate')).toBeVisible()
    await expect(page.getByTestId('heading-cover-current')).toHaveCount(0)
    await expectAccessible(page, at('sponsor AI cover panel'))
    await panel.getByTestId('ai-cover-generate').click()
    const dialog = page.getByTestId('ai-cover-dialog')
    await expect(dialog.getByTestId('ai-notice')).toBeVisible()
    await expectAccessible(page, at('sponsor AI cover dialog'))
    await dialog.getByTestId('ai-notice-agree').click()
    await dialog.getByTestId('ai-cover-submit').click()
    await expect(dialog).toBeHidden()

    const choice = page.getByTestId('ai-cover-choice')
    await expect(choice).toBeVisible({ timeout: 60_000 })
    const candidates = choice.getByTestId('ai-cover-candidate')
    await expect(candidates).toHaveCount(3)
    await expect(candidates.nth(0).getByRole('img')).toBeVisible()
    await expect(choice.getByTestId('ai-cover-apply')).toBeDisabled()
    await expectAccessible(page, at('sponsor AI cover choice'))
    // A click on the picture picks it, as its radio does.
    await candidates.nth(2).getByTestId('ai-cover-picture').click()
    await expect(candidates.nth(2).getByTestId('ai-cover-pick')).toBeChecked()
    await candidates.nth(1).getByTestId('ai-cover-pick').click()
    await choice.getByTestId('ai-cover-apply').click()
    await expect(choice).toBeHidden()
    // The chosen picture is the quiz's cover now, shown by the heading; the quiz is AI generated (M-20).
    await expect(page.getByTestId('heading-cover-current').getByRole('img')).toBeVisible()
    const quizzes = await query(request, sponsor.token, 'qb.sponsor.quizzes', {})
    const row = quizzes.find((r) => r.quizId === quizId)
    expect(row?.aiGenerated).toBe(true)
    expect(row?.cover).toBeTruthy()

    // Questions from the article and the PDF: the PDF goes as the file itself.
    await page.getByTestId('tab-questions').click()
    await page.getByTestId('ai-panel').getByTestId('ai-generate').click()
    const ask = page.getByTestId('ai-dialog')
    const materials = ask.getByTestId('ai-material')
    await expect(materials).toHaveCount(2)
    await expect(materials.nth(1)).toContainText('The PDF itself is sent')
    // The notice was accepted with the covers.
    await expect(ask.getByTestId('ai-notice')).toHaveCount(0)
    await ask.getByTestId('ai-count').fill('1')
    await ask.getByTestId('ai-submit').click()
    await expect(ask).toBeHidden()
    await expect(page.getByTestId('ai-review')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('ai-review').getByTestId('ai-candidate')).toHaveCount(1)
  })
}
