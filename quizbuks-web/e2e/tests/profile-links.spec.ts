import { PNG } from './content'
import { approvedSponsor } from './publishing'
import { expect, expectAccessible, prepareQuizbuks, rows, setAppearance, signIn, test, unique } from './support'

/*
 * F-C6 (docs/quizbuks/plans/impl/F-C6.md): a profile package whose cover is a link. The server downloads links only
 * from public hosts (decision D75), and no public image host is reachable from here, so this follows the failure
 * path that matters to a sponsor: a link the server refuses is shown with its reason, nothing is imported until the
 * sponsor chooses, and an image of their own in its place completes the import. The download that succeeds is the
 * back end's test (ProfileLinkImagesIT) and the page's (transfer.test.tsx).
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const PAGES = 'urn:jabiz:dataset:me:QbSponsorPage'

for (const appearance of ['light', 'dark'] as const) {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)

  test(`a refused link is shown with its reason and replaced by the sponsor's own image (${appearance})`, async ({
    page, request }) => {
    await setAppearance(page, appearance)
    const { email, token } = await approvedSponsor(request, 'Lena')
    const handle = unique('links-').toLowerCase().replace(/[^a-z0-9-]/g, '').slice(0, 28)
    const document = {
      format: 'quizbuks-profile', schema: 1, languages: ['ja'],
      basics: { contactName: 'Lena', company: 'Lena Co' },
      page: { handle, headline: { ja: 'おいしいお茶' }, cover: { url: 'https://127.0.0.1/cover.png' } },
      products: [],
    }

    await signIn(page, '/sponsor/', email)
    // Signed in: the menu is there before the page is left (a click on submit alone does not wait for it).
    await page.getByRole('link', { name: 'Sponsor profile' }).click()
    await expect(page.getByTestId('profile-transfer')).toBeVisible()
    await page.getByTestId('profile-import-file').setInputFiles({ name: 'lena.quizbuks-profile.json',
      mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(document)) })
    const dialog = page.getByTestId('profile-import-dialog')
    const link = dialog.getByTestId('profile-import-link-0')
    await expect(link).toHaveAttribute('data-state', 'idle')

    // The server refuses an address that is not a public one, and says why; nothing is imported.
    await dialog.getByTestId('profile-import-confirm').click()
    await expect(link).toHaveAttribute('data-state', 'failed')
    await expect(dialog.getByTestId('profile-import-link-0-reasons').getByRole('listitem')).not.toHaveCount(0)
    await expect(dialog.getByTestId('profile-import-confirm')).toBeDisabled()
    expect((await rows(request, token, PAGES, [])).length).toBe(0)
    await expectAccessible(page, at('sponsor profile import, a refused link'))

    // An image of their own in its place: the import completes and the page has it.
    await dialog.getByTestId('profile-import-link-0-upload').click()
    await dialog.getByTestId('profile-import-link-file').setInputFiles({ name: 'cover.png', mimeType: 'image/png',
      buffer: PNG })
    await expect(link).toHaveAttribute('data-state', 'done')
    await dialog.getByTestId('profile-import-confirm').click()
    await expect(dialog).toBeHidden()
    await expect.poll(async () => (await rows(request, token, PAGES, []))[0]?.attributes.cover ?? null,
      { message: 'the page has the sponsor\'s own cover' }).not.toBeNull()
  })
}
