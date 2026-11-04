import { aiReady, quizForAi } from './ai'
import { PDF, POLICY, upload } from './content'
import { approvedSponsor } from './publishing'
import { expect, expectAccessible, prepareQuizbuks, run, signIn, test, unique, setAppearance } from './support'

/**
 * PDF materials (docs/quizbuks/plans/Q8-ai.md, Q8b; M-26) with the provider answered by the local stub: an approved
 * sponsor asks for questions from the quiz's article and its PDF, which is sent as the file itself; the AI notice on
 * first use. Every step passes axe, light and dark. (AI covers were withdrawn, Q98a.)
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
  await aiReady(request)
})

for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`a sponsor asks the AI for questions from a PDF (${appearance})`, async ({ page, request }) => {
    test.setTimeout(240_000)
    await setAppearance(page, appearance)
    const sponsor = await approvedSponsor(request, 'Cora')
    const title = unique('PDF quiz ')
    const quizId = await quizForAi(request, sponsor.token, title)
    const pdf = await upload(request, sponsor.token, POLICY.pdf, 'material.pdf', 'application/pdf', PDF)
    await run(request, sponsor.token, 'QB_MATERIAL_SAVE', { quizId, kind: 'PDF', title: 'The paper',
      description: 'About the Seine', pdf })
    await signIn(page, '/sponsor/', sponsor.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await page.goto(`/sponsor/quizzes/${quizId}`)

    // Questions from the article and the PDF: the PDF goes as the file itself.
    await page.getByTestId('tab-questions').click()
    await page.getByTestId('ai-panel').getByTestId('ai-generate').click()
    const ask = page.getByTestId('ai-dialog')
    const materials = ask.getByTestId('ai-material')
    await expect(materials).toHaveCount(2)
    await expect(materials.nth(1)).toContainText('The PDF itself is sent')
    // The AI notice on first use.
    await expect(ask.getByTestId('ai-notice')).toBeVisible()
    await expectAccessible(page, at('sponsor AI dialog with a PDF'))
    await ask.getByTestId('ai-notice-agree').click()
    await ask.getByTestId('ai-count').fill('1')
    await ask.getByTestId('ai-submit').click()
    await expect(ask).toBeHidden()
    await expect(page.getByTestId('ai-review')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('ai-review').getByTestId('ai-candidate')).toHaveCount(1)
  })
}
