import { readFile } from 'node:fs/promises'
import { query, sponsor } from './content'
import { decideAsOperator } from './operators'
import { fund, fundedSponsor } from './play'
import { publicationOf } from './publishing'
import { expect, expectAccessible, prepareQuizbuks, rows, run, signIn, signOut, test, unique } from './support'

/*
 * The sponsor package (F-C4, docs/quizbuks/plans/impl/F-C4.md): a Sponsor exports their profile, a knowledge map, a
 * quiz with two versions used by publications and the publications (one bound to the map's node) from the overview;
 * a new Sponsor imports the file: the application form is filled in, the map is a draft, the quiz has both versions,
 * the publications are drafts on them, one bound to the imported node; "Submit all drafts" sends the map and the
 * unbound publication into review and lists the bound one with why (its map is not in effect yet).
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

const PUBLICATIONS = 'urn:jabiz:dataset:sponsor:QbPublication'

test('a sponsor package is exported, imported into a new account and its drafts submitted', async ({ page,
  request }) => {
  test.setTimeout(300_000)
  // The Sponsor whose content is exported: approved, with a map (a draft), a quiz of two versions and two drafts.
  const source = await fundedSponsor(request)
  const company = (await rows(request, source.token, 'urn:jabiz:dataset:sponsor:QbSponsor', []))[0].attributes
    .company as string
  const tree = await run(request, source.token, 'QB_TREE_CREATE', { ownerKind: 'SPONSOR', name: { en: 'Coffee map' } })
  const map = await run(request, source.token, 'QB_TREE_DRAFT_SAVE', { treeId: tree.treeId,
    baseRevision: tree.revision, name: { en: 'Coffee map' }, nodes: [
      { key: 'basics', name: { en: 'Coffee basics' }, condition: { op: 'ALL', items: [{ passed: true }] } }] })
  const basics = (map.nodeIds as Record<string, string>).basics
  const title = unique('Packed quiz ')
  const quiz = await run(request, source.token, 'QB_QUIZ_SAVE', { title, intro: 'Beans.', timeLimitSec: 300 })
  const quizId = quiz.quizId as string
  const question = await run(request, source.token, 'QB_QUESTION_SAVE', { quizId, stem: 'Arabica or robusta?',
    points: 1, options: [{ text: 'Arabica', correct: true }, { text: 'Robusta' }] })
  const v1 = await run(request, source.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  await run(request, source.token, 'QB_QUESTION_SAVE', { quizId, questionId: question.itemId,
    stem: 'Which beans are sweeter?', points: 1, options: [{ text: 'Arabica', correct: true }, { text: 'Robusta' }] })
  const v2 = await run(request, source.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const start = Date.now() + 2 * 86_400_000
  const draft = (versionId: unknown, name: string, more: Record<string, unknown> = {}) =>
    run(request, source.token, 'QB_PUBLICATION_SAVE', { versionId, name, regions: ['GLOBAL'],
      startAt: new Date(start).toISOString(), endAt: new Date(start + 7 * 86_400_000).toISOString(), passScore: 1,
      budgetCap: 500, maxWinners: 5, tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1,
        reward: 20 }], ...more })
  const boundName = unique('Bound ')
  const freeName = unique('Free ')
  await draft(v1.versionId, boundName, { treeId: tree.treeId, treeNodeId: basics })
  await draft(v2.versionId, freeName)

  // Exported from the overview: version 2 of the format, only content and settings.
  await signIn(page, '/sponsor/', source.email)
  // Signed in before leaving the page: a navigation now would cut the sign-in request off.
  await expect(page.getByTestId('dashboard-status')).toBeVisible()
  await page.goto('/sponsor/profile')
  await expect(page.getByTestId('sponsor-package')).toBeVisible()
  const downloaded = page.waitForEvent('download')
  await page.getByTestId('sponsor-package-export').click()
  const file = await downloaded
  expect(file.suggestedFilename()).toMatch(/\.quizbuks-sponsor\.json$/)
  const path = await file.path()
  const text = await readFile(path, 'utf8')
  const document = JSON.parse(text)
  expect(document).toMatchObject({ format: 'quizbuks.sponsor-package', version: 2 })
  expect(document.trees).toHaveLength(1)
  const packed = document.quizzes.find((q: { versions: { file: { quiz: { title: string } } }[] }) =>
    q.versions.some((v) => v.file.quiz.title === title))
  expect(packed.versions.map((v: { label: string }) => v.label)).toEqual(['v1', 'v2'])
  expect(packed.current).toBeNull()
  expect(document.publications.map((p: { settings: { name: string } }) => p.settings.name))
    .toEqual(expect.arrayContaining([boundName, freeName]))
  for (const word of ['password', 'approvalRequestId', 'submittedAt']) expect(text).not.toContain(word)
  await signOut(page)

  // A new Sponsor (not applied yet) imports it.
  const target = await sponsor(request, 'Nova')
  await signIn(page, '/sponsor/', target.email)
  await expect(page).toHaveURL(/\/sponsor\/profile\/basics$/)
  await page.goto('/sponsor/profile')
  await page.getByTestId('sponsor-package-file').setInputFiles(path)
  const dialog = page.getByTestId('sponsor-package-dialog')
  const plan = dialog.getByTestId('sponsor-package-plan')
  await expect(plan).toHaveAttribute('data-mode', 'FULL')
  await expect(plan.getByTestId('sponsor-package-makes-trees')).toContainText('1')
  await expect(plan.getByTestId('sponsor-package-makes-publications')).toContainText('2')
  await expectAccessible(page, 'sponsor package check')
  await dialog.getByTestId('sponsor-package-confirm').click()
  await expect(dialog.getByTestId('sponsor-package-done')).toBeVisible({ timeout: 120_000 })

  // The application form is filled in with the package's basics.
  await dialog.getByTestId('sponsor-package-basics').click()
  await expect(page.getByTestId('onboarding-company')).toHaveValue(company)

  // The map (a draft), the quiz with both versions, the drafts on them, one bound to the imported node.
  const [importedTree] = await query(request, target.token, 'qb.sponsor.package-trees', {})
  expect(importedTree).toMatchObject({ currentVersionId: null, status: 'DRAFT' })
  const [importedNode] = await query(request, target.token, 'qb.sponsor.package-tree-nodes',
    { versionId: importedTree.versionId })
  expect(importedNode.nodeId).not.toBe(basics)
  const quizzes = await query(request, target.token, 'qb.sponsor.quizzes', { q: title })
  expect(quizzes).toHaveLength(1)
  const versions = await query(request, target.token, 'qb.sponsor.quiz-versions', { quizId: quizzes[0].quizId })
  expect(versions.map((v) => v.label).sort()).toEqual(['v1', 'v2'])
  const drafts = (await rows(request, target.token, PUBLICATIONS, [])).map((r) => r.attributes)
  const bound = drafts.find((p) => p.name === boundName)!
  const free = drafts.find((p) => p.name === freeName)!
  expect(bound).toMatchObject({ status: 'DRAFT', treeId: importedTree.treeId, treeNodeId: importedNode.nodeId })
  expect(free).toMatchObject({ status: 'DRAFT', treeId: null })
  expect(versions.find((v) => v.versionId === bound.versionId)?.label).toBe('v1')
  expect(versions.find((v) => v.versionId === free.versionId)?.label).toBe('v2')

  // The Sponsor applies with the filled form and is approved; then every draft goes into review.
  const applied = await run(request, target.token, 'QB_SPONSOR_APPLY', { displayName: 'Nova', company,
    acceptTerms: true })
  if (applied.approval === 'PENDING') await decideAsOperator(request, target.token, applied.approvalRequestId)
  await expect.poll(async () => (await rows(request, target.token, 'urn:jabiz:dataset:sponsor:QbSponsor', []))[0]
    ?.attributes.reviewStatus, { timeout: 30_000 }).toBe('APPROVED')
  await fund(request, target.token)
  await page.goto('/sponsor/publications')
  await page.getByTestId('submit-all-drafts').click()
  await page.getByRole('button', { name: 'Submit all' }).click()
  const results = page.getByTestId('submit-all-results')
  await expect(results).toBeVisible({ timeout: 60_000 })
  await expect(results.locator('[data-testid="submit-all-result"][data-kind="tree"][data-ok="true"]')).toHaveCount(1)
  await expect(results.locator('[data-testid="submit-all-result"][data-ok="false"]')).toContainText(boundName)
  await expectAccessible(page, 'submit all drafts')
  expect((await publicationOf(request, target.token, String(free.publicationId))).status).toBe('PENDING_REVIEW')
  const [inReview] = await query(request, target.token, 'qb.sponsor.package-trees', {})
  expect(inReview.status).toBe('REVIEW')
})
