import { readFile } from 'node:fs/promises'
import { query, sponsor } from './content'
import { decideAsOperator, operatorWithLead, reviewerFor, signInOperatorDesk, sponsorOfOperator } from './operators'
import { fund, fundedSponsor, taker, untilLive } from './play'
import { allowRewardMethods, decideReview, publicationOf, typeTime } from './publishing'
import { ensureOnboardingGrant, SAMPLE_PICK, samplePackage } from './sample-package'
import { operatorTree } from './tree'
import { adminToken, expect, expectAccessible, prepareQuizbuks, rows, run, signIn, signOut, test, unique }
  from './support'

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
  // F-F3d: the packages are in the head's "Import and export" menu.
  await page.getByTestId('profile-transfer').click()
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
  expect(packed.versions.map((v: { label: string }) => v.label)).toEqual(['v1.0', 'v1.1'])
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
  expect(versions.map((v) => v.label).sort()).toEqual(['v1.0', 'v1.1'])
  const drafts = (await rows(request, target.token, PUBLICATIONS, [])).map((r) => r.attributes)
  const bound = drafts.find((p) => p.name === boundName)!
  const free = drafts.find((p) => p.name === freeName)!
  expect(bound).toMatchObject({ status: 'DRAFT', treeId: importedTree.treeId, treeNodeId: importedNode.nodeId })
  expect(free).toMatchObject({ status: 'DRAFT', treeId: null })
  expect(versions.find((v) => v.versionId === bound.versionId)?.label).toBe('v1.0')
  expect(versions.find((v) => v.versionId === free.versionId)?.label).toBe('v1.1')

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

/*
 * F-F11c (docs/quizbuks/plans/impl/F-F11.md §6.3): a quiz's content replaced by the next is cleared in steps first
 * (F-F11a/b/d), and each request is cut by the bytes `qb.content.import.batch-bytes` allows, the first step too. The
 * tests set the parameter to its least (64 KiB) so that a small package is cut, and set it back.
 */
const BATCH_BYTES = 'qb.content.import.batch-bytes'
const LEAST_BYTES = 65_536
const PACKAGE_IMPORTS = 'urn:jabiz:dataset:me:QbPackageImport'
const STEP = '/api/processes/QB_PACKAGE_IMPORT_STEP/latest'

/** A question of about a kilobyte (300 CJK characters), numbered so that the stems can be told apart. */
const stemOf = (version: string, no: number) => `${version} ${no}：${'咖啡豆的产地与烘焙'.repeat(33)}`

function quizFile(title: string, version: string, count: number, materials: Record<string, unknown>[] = []) {
  return { format: 'quizbuks.quiz', formatVersion: 1, exportedAt: '2026-11-12T00:00:00Z',
    quiz: { kind: 'QUIZ', title, timeLimitSec: 300 }, materials,
    questions: Array.from({ length: count }, (_, i) => ({ stem: stemOf(version, i + 1), points: 1,
      options: [{ text: 'Yes', correct: true }, { text: 'No' }] })),
    files: {} }
}

/** A package of one quiz in two versions (the second replaces the first's content), nothing else. */
function packageOf(v1: Record<string, unknown>, v2: Record<string, unknown>): Buffer {
  return Buffer.from(JSON.stringify({ format: 'quizbuks.sponsor-package', version: 2,
    exportedAt: '2026-11-12T00:00:00Z', profile: null, trees: [], publications: [],
    quizzes: [{ key: 'q1', current: null, versions: [{ key: 'v1', label: 'v1.0', file: v1 },
      { key: 'v2', label: 'v1.1', file: v2 }] }] }))
}

/** The steps the page sends, as sent: their keys and their quiz documents' bytes. */
async function stepsSent(page: import('@playwright/test').Page) {
  const sent: { step: string; bytes: number }[] = []
  // Read where the request is held (a large body is not in the request event): the step, and its document's bytes.
  await page.route(`**${STEP}`, async (route) => {
    const raw = route.request().postDataBuffer()
    const body = JSON.parse(raw?.toString('utf8') ?? '{}') as Record<string, any>
    const document = body.quiz?.document ?? body.questions?.document
    sent.push({ step: String(body.step), bytes: document ? Buffer.byteLength(JSON.stringify(document)) : 0 })
    await route.fallback()
  })
  return sent
}

/** Runs the body with the request's bytes at their least, then sets back what they were. */
async function withLeastBytes(request: import('@playwright/test').APIRequestContext, token: string,
  body: (admin: string) => Promise<void>) {
  const [limits] = await query(request, token, 'qb.sponsor.quiz-limits', {})
  const before = String(limits.importBatchBytes)
  const admin = await adminToken(request)
  await run(request, admin, 'PARAM_SET', { key: BATCH_BYTES, value: String(LEAST_BYTES) })
  try {
    await body(admin)
  } finally {
    await run(request, admin, 'PARAM_SET', { key: BATCH_BYTES, value: before })
  }
}

async function chooseFile(page: import('@playwright/test').Page, name: string, file: Buffer) {
  await page.goto('/sponsor/profile')
  await page.getByTestId('sponsor-package-file').setInputFiles({ name: `${name}.quizbuks-sponsor.json`,
    mimeType: 'application/json', buffer: file })
  return page.getByTestId('sponsor-package-dialog')
}

async function stemsOf(request: import('@playwright/test').APIRequestContext, token: string, title: string) {
  const quizzes = await query(request, token, 'qb.sponsor.quizzes', { q: title })
  expect(quizzes).toHaveLength(1)
  const quizId = quizzes[0].quizId
  const stems: string[] = []
  for (let offset = 0; ; offset += 100) {
    const response = await request.post('/api/queries/qb.sponsor.quiz-questions', {
      headers: { Authorization: `Bearer ${token}` }, data: { params: { quizId }, offset, limit: 100 } })
    expect(response.status()).toBe(200)
    const items = ((await response.json()).items ?? []) as Record<string, unknown>[]
    stems.push(...items.map((item) => String(item.stem)))
    if (items.length < 100) break
  }
  const versions = await query(request, token, 'qb.sponsor.quiz-versions', { quizId })
  return { stems, versions: versions.map((v) => String(v.label)).sort() }
}

test('a replaced content is cleared in steps and every request, the first too, is cut by the bytes', async ({ page,
  request }) => {
  test.setTimeout(300_000)
  const target = await sponsor(request, 'Bytes')
  const title = unique('Cut quiz ')
  // About 120 KB and 150 KB of questions: each content is cut into a first step and batches under 64 KiB.
  const file = packageOf(quizFile(title, 'A', 120), quizFile(title, 'B', 150))
  await withLeastBytes(request, target.token, async () => {
    await signIn(page, '/sponsor/', target.email)
    // Not applied yet: the sign-in ends on the application form.
    await expect(page).toHaveURL(/\/sponsor\/profile\/basics$/)
    const sent = await stepsSent(page)
    const dialog = await chooseFile(page, 'cut', file)
    await expect(dialog.getByTestId('sponsor-package-plan')).toHaveAttribute('data-mode', 'FULL')
    await expect(dialog.getByTestId('sponsor-package-oversized')).toHaveCount(0)
    await dialog.getByTestId('sponsor-package-confirm').click()
    await expect(dialog.getByTestId('sponsor-package-done')).toBeVisible({ timeout: 180_000 })

    const keys = sent.map((s) => s.step)
    // The first version's content, then its version; the second's cleared before it is brought.
    expect(keys.indexOf('quiz:q1:v:v1:content')).toBe(0)
    expect(keys.filter((k) => /^quiz:q1:v:v1:batch:\d+$/.test(k)).length).toBeGreaterThanOrEqual(1)
    expect(keys).toContain('quiz:q1:v:v2:clear:1')
    expect(keys.indexOf('quiz:q1:v:v2:clear:1')).toBeLessThan(keys.indexOf('quiz:q1:v:v2:content'))
    expect(keys.indexOf('quiz:q1:v:v1:version')).toBeLessThan(keys.indexOf('quiz:q1:v:v2:clear:1'))
    expect(keys.filter((k) => /^quiz:q1:v:v2:batch:\d+$/.test(k)).length).toBeGreaterThanOrEqual(2)
    // Every request's quiz document within the bytes, the first step of each content included.
    for (const s of sent) expect(s.bytes, s.step).toBeLessThanOrEqual(LEAST_BYTES)
  })
  // The draft is the second version's whole content; the first is kept as its version.
  const { stems, versions } = await stemsOf(request, target.token, title)
  expect(stems).toHaveLength(150)
  expect(stems.every((stem) => stem.startsWith('B '))).toBe(true)
  expect(versions).toEqual(['v1.0', 'v1.1'])
})

test('a question bank the bytes cannot take is pointed out and not started', async ({ page, request }) => {
  test.setTimeout(180_000)
  const target = await sponsor(request, 'Oversized')
  const title = unique('Oversized quiz ')
  // Two articles of 15 000 CJK characters: the heading and materials alone are about 90 KB, over 64 KiB.
  const article = (no: number) => ({ no, kind: 'ARTICLE', title: `Article ${no}`, body: '烘焙'.repeat(7_500) })
  const file = packageOf(quizFile(title, 'A', 3, [article(1), article(2)]), quizFile(title, 'B', 3))
  await withLeastBytes(request, target.token, async () => {
    await signIn(page, '/sponsor/', target.email)
    // Not applied yet: the sign-in ends on the application form.
    await expect(page).toHaveURL(/\/sponsor\/profile\/basics$/)
    const dialog = await chooseFile(page, 'oversized', file)
    await expect(dialog.getByTestId('sponsor-package-oversized')).toContainText(title)
    await expect(dialog.getByTestId('sponsor-package-confirm')).toBeDisabled()
    await expectAccessible(page, 'sponsor package over the request size')
  })
  expect(await query(request, target.token, 'qb.sponsor.quizzes', { q: title })).toHaveLength(0)
})

test('an import stopped after clearing goes on with what it began with and does not clear again', async ({ page,
  request }) => {
  test.setTimeout(300_000)
  const target = await sponsor(request, 'Stopped')
  const title = unique('Stopped quiz ')
  const file = packageOf(quizFile(title, 'A', 120), quizFile(title, 'B', 150))
  let importId = ''
  let total = 0
  await withLeastBytes(request, target.token, async (admin) => {
    await signIn(page, '/sponsor/', target.email)
    // Not applied yet: the sign-in ends on the application form.
    await expect(page).toHaveURL(/\/sponsor\/profile\/basics$/)
    // The step that brings the second version fails once, after its clearing steps are done.
    let failed = false
    await page.route(`**${STEP}`, async (route) => {
      const body = JSON.parse(route.request().postDataBuffer()?.toString('utf8') ?? '{}') as Record<string, unknown>
      if (!failed && body.step === 'quiz:q1:v:v2:content') {
        failed = true
        await route.fulfill({ status: 503, contentType: 'application/problem+json',
          body: JSON.stringify({ title: 'Service unavailable', status: 503 }) })
        return
      }
      await route.continue()
    })
    const dialog = await chooseFile(page, 'stopped', file)
    await dialog.getByTestId('sponsor-package-confirm').click()
    await expect(dialog.getByTestId('sponsor-package-failed')).toBeVisible({ timeout: 180_000 })
    // The draft is empty until the import goes on: the page says so.
    await expect(dialog.getByTestId('sponsor-package-emptied')).toBeVisible()
    await expectAccessible(page, 'sponsor package stopped with an empty draft')
    const [open] = (await rows(request, target.token, PACKAGE_IMPORTS,
      [{ field: 'status', op: 'eq', value: 'RUNNING' }])).map((r) => r.attributes)
    importId = String(open.importId)
    total = Number(open.stepsTotal)
    expect(Number(open.stepsDone)).toBeLessThan(total)

    // The limit changes before the import goes on: it keeps the cut it began with.
    await run(request, admin, 'PARAM_SET', { key: BATCH_BYTES, value: String(LEAST_BYTES * 4) })
    await page.unroute(`**${STEP}`)
    await page.goto('/sponsor/profile')
    await expect(page.getByTestId('sponsor-package-open')).toBeVisible()
    const sent = await stepsSent(page)
    const again = await chooseFile(page, 'stopped', file)
    await again.getByTestId('sponsor-package-confirm').click()
    await expect(again.getByTestId('sponsor-package-done')).toBeVisible({ timeout: 180_000 })
    // Only what was left is sent: the content, its batches and its version; no clearing again.
    const keys = sent.map((s) => s.step)
    expect(keys[0]).toBe('quiz:q1:v:v2:content')
    expect(keys.some((k) => k.includes(':clear:'))).toBe(false)
    for (const s of sent) expect(s.bytes, s.step).toBeLessThanOrEqual(LEAST_BYTES)
  })
  const record = (await rows(request, target.token, PACKAGE_IMPORTS, [])).map((r) => r.attributes)
    .find((r) => r.importId === importId)
  expect(record).toMatchObject({ status: 'DONE', stepsTotal: total, stepsDone: total })
  const { stems, versions } = await stemsOf(request, target.token, title)
  expect(stems).toHaveLength(150)
  expect(stems.every((stem) => stem.startsWith('B '))).toBe(true)
  expect(versions).toEqual(['v1.0', 'v1.1'])
})

/*
 * F-F11c (docs/quizbuks/plans/impl/F-F11.md section 6.3): a sample Sponsor comes back after the test system was reset,
 * in the order of the recovery (operations.md): the Sponsor is approved by its operator, the operator's lead confirms
 * the onboarding Kudos for several Sponsors at once, the Sponsor imports its package, loads the digital prize codes
 * (a package never carries them), submits every draft, the map is reviewed, the publication bound to its node is
 * submitted again, the publications are reviewed, and a Taker is given as many questions as the prize publication draws
 * from its long bank. The switch of the onboarding Kudos is a global parameter: the test is in a serial describe and
 * puts it back.
 */
test.describe.serial('a sample Sponsor', () => {
  test('a sample Sponsor comes back from its package', async ({ page, request, browser }) => {
    test.setTimeout(300_000)
    const tag = unique('Sample ')
    const names = { bankTitle: unique('Sample bank '), videoTitle: unique('Sample film '), mapName: unique('Sample map '),
      node: 'Coffee basics', boundName: unique('Sample bound '), prizeName: unique('Sample prize ') }
    const restore = await ensureOnboardingGrant(request)
    const desk = await browser.newContext()
    try {
      // The operator, its lead, and two approved Sponsors of it: the one that comes back and one more to tick with it.
      const operator = await operatorWithLead(request, 'Samp')
      const sample = await sponsorOfOperator(request, operator, 'Mika', `${tag} Mika Co`)
      const other = await sponsorOfOperator(request, operator, 'Neo', `${tag} Neo Co`)
      expect(await query(request, sample.token, 'qb.sponsor.fee-account-lots', {})).toEqual([])
      const [limits] = await query(request, sample.token, 'qb.sponsor.quiz-limits', {})
      const file = samplePackage(Number(limits.importBatch), names)

      // The lead ticks both in the operator's Sponsor list and confirms them in one go: one grant each.
      const lead = await desk.newPage()
      await signInOperatorDesk(lead, operator)
      await lead.goto(`/operator/qb/operator/sponsors?q=${encodeURIComponent(tag)}`)
      for (const s of [sample, other]) {
        await lead.locator(`[data-testid="sponsor-row"][data-id="${s.userId}"]`).getByTestId('sponsor-grant-select')
          .click()
      }
      const confirm = lead.getByTestId('operator-sponsors-confirm-selected')
      await expect(confirm).toBeEnabled()
      await confirm.click()
      for (const s of [sample, other]) {
        await expect(lead.locator(`[data-testid="sponsor-row"][data-id="${s.userId}"]`)
          .getByTestId('sponsor-grant-result')).toHaveAttribute('data-state', 'ok')
      }
      for (const s of [sample, other]) {
        const lots = await query(request, s.token, 'qb.sponsor.fee-account-lots', {})
        expect(lots.map((l) => l.source), 'one onboarding grant').toEqual(['ONBOARDING'])
      }

      // The prize methods are not in a package: the content administrator allows the digital prize (step 6 of the recovery).
      await allowRewardMethods(request, sample.token, ['PLATFORM', 'DIGITAL_PRIZE'])

      // The Sponsor imports the package on its page; the bank is cut into more than one batch.
      await signIn(page, '/sponsor/', sample.email)
      await expect(page.getByTestId('dashboard-status')).toBeVisible()
      await page.goto('/sponsor/profile')
      await page.getByTestId('sponsor-package-file').setInputFiles({ name: 'sample.quizbuks-sponsor.json',
        mimeType: 'application/json', buffer: file })
      const dialog = page.getByTestId('sponsor-package-dialog')
      const plan = dialog.getByTestId('sponsor-package-plan')
      await expect(plan).toHaveAttribute('data-mode', 'FULL')
      await expect(plan.getByTestId('sponsor-package-makes-trees')).toContainText('1')
      await expect(plan.getByTestId('sponsor-package-makes-quizzes')).toContainText('2')
      await expect(plan.getByTestId('sponsor-package-makes-publications')).toContainText('2')
      await expect(plan.getByTestId('sponsor-bank-batch-info')).toBeVisible()
      await dialog.getByTestId('sponsor-package-confirm').click()
      await expect(dialog.getByTestId('sponsor-package-done')).toBeVisible({ timeout: 180_000 })

      const drafts = (await rows(request, sample.token, PUBLICATIONS, [])).map((r) => r.attributes)
      const bound = drafts.find((p) => p.name === names.boundName)!
      const prize = drafts.find((p) => p.name === names.prizeName)!
      expect(bound).toMatchObject({ status: 'DRAFT' })
      expect(bound.treeNodeId, 'bound to the imported node').toBeTruthy()
      expect(prize).toMatchObject({ status: 'DRAFT', rewardMethod: 'DIGITAL_PRIZE', pickCount: SAMPLE_PICK })
      const [bank] = await query(request, sample.token, 'qb.sponsor.quizzes', { q: names.bankTitle })
      expect(Number(bank.questionCount), 'every question of the bank came').toBe(Number(limits.importBatch) + 3)

      // The imported drafts start at the next hour: the Sponsor moves the start of each to a minute ago, so that the
      // test need not wait for the hour.
      for (const draft of [bound, prize]) {
        const id = String(draft.publicationId)
        await page.goto(`/sponsor/publications/${id}?step=1`)
        await expect(page.getByTestId('publication-step-title')).toHaveText('Time and rewards')
        await typeTime(page, 'publication-start', new Date(Date.now() - 60_000))
        await page.getByTestId('publication-next').click()
        await expect.poll(async () => Date.parse(String((await publicationOf(request, sample.token, id)).startAt)),
          { message: 'the start is saved' }).toBeLessThan(Date.now())
      }

      // The codes of the digital prize: two for the two winners it allows.
      await page.goto(`/sponsor/publications/${prize.publicationId}`)
      const pool = page.getByTestId('digital-pool')
      await expect(pool).toBeVisible()
      await pool.getByTestId('digital-pool-lines').fill('SAMPLE-0001-AAAA\nSAMPLE-0002-BBBB\n')
      await pool.getByTestId('digital-pool-load').click()
      await expect(pool.getByTestId('digital-pool-available')).toHaveText(/2/)

      // Submit every draft: the map and the prize publication go into review; the bound one waits for its map.
      await fund(request, sample.token)
      await page.goto('/sponsor/publications')
      await page.getByTestId('submit-all-drafts').click()
      await page.getByRole('button', { name: 'Submit all' }).click()
      const results = page.getByTestId('submit-all-results')
      await expect(results).toBeVisible({ timeout: 60_000 })
      await expect(results.locator('[data-testid="submit-all-result"][data-kind="tree"][data-ok="true"]'))
        .toHaveCount(1)
      await expect(results.locator('[data-testid="submit-all-result"][data-kind="publication"][data-ok="true"]'))
        .toContainText(names.prizeName)
      await expect(results.locator('[data-testid="submit-all-result"][data-ok="false"]'))
        .toContainText(names.boundName)

      // The operator's other member puts the map in effect; the bound publication is submitted again.
      const [map] = await query(request, sample.token, 'qb.sponsor.package-trees', {})
      const reviewer = await reviewerFor(request, sample.token)
      const waiting = await operatorTree(request, reviewer, String(map.treeId))
      expect(waiting?.approvalRequestId, 'the map waits for the operator').toBeTruthy()
      await run(request, reviewer, 'APPROVAL_DECIDE', { requestId: waiting!.approvalRequestId, decision: 'APPROVE' })
      await expect.poll(async () => (await query(request, sample.token, 'qb.sponsor.package-trees', {}))[0]
        ?.currentVersionId ?? null, { message: 'the map is in effect', timeout: 30_000 }).not.toBeNull()
      await results.getByRole('button', { name: 'Close' }).first().click()
      await page.getByTestId('submit-all-drafts').click()
      await page.getByRole('button', { name: 'Submit all' }).click()
      await expect(results.locator('[data-testid="submit-all-result"][data-kind="publication"][data-ok="true"]'))
        .toContainText(names.boundName, { timeout: 60_000 })

      // Both publications are reviewed and live.
      for (const draft of [bound, prize]) {
        const id = String(draft.publicationId)
        await decideReview(request, sample.token, id, 'APPROVE')
        await expect.poll(async () => (await publicationOf(request, sample.token, id)).status,
          { message: 'the publication is live', timeout: 30_000 }).toBe('LIVE')
      }

      // A Taker answers the prize publication: as many questions as it draws from the long bank.
      const me = await taker(request, 'Mei')
      await untilLive(request, me.token, String(prize.publicationId))
      await signOut(page)
      await signIn(page, '/taker/', me.email)
      await expect(page.getByTestId('home')).toBeVisible()
      await page.goto(`/taker/p/${prize.publicationId}`)
      await page.getByTestId('detail-start').click()
      await expect(page.getByTestId('play-progress')).toHaveText(`Question 1 of ${SAMPLE_PICK}`)
    } finally {
      await desk.close()
      await restore()
    }
  })
})
