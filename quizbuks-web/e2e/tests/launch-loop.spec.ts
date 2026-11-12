import { readFileSync } from 'node:fs'
import { certificatesOf, verifyPage } from './certificates'
import { query } from './content'
import { decideAsOperator, reviewerFor } from './operators'
import { searchHome } from './play'
import { boundPublicationOf, CAPITALS, operatorTree, platformMap, pass } from './tree'
import { answerQuiz, GRANT, loopCast, phone, rememberParams, STEP_TIMEOUT_MS, type Loop } from './launch-loop'
import { contentAdmin } from './legal'
import { approvedSponsor, fund } from './wallet'
import {
  accessibleInBoth, expect, mailTo, openArea, PASSWORD, prepareQuizbuks, run, setControlledParam, signIn, signOut,
  takerArea, test, tokenOf, unique,
} from './support'

/*
 * The first launch's closed loop (docs/quizbuks/first-launch-spec.md F-F12, plan docs/quizbuks/plans/impl/F-F12.md):
 * one story with one set of people, from a Sponsor drawing a knowledge map to a Sponsor lighting the platform's node.
 * Each step is a test of its own in one serial describe: a step that fails stops the story there, and the report says
 * where. A step uses the page where somebody would sit and the API for the preparing that other specs cover.
 *
 *   0  the people: an operator with its official account and a reviewer, Sora (a Sponsor of the operator), Rin and Kai
 *   1  Sora's map of two nodes, put in effect by another member of her operator
 *   2' F-M5: submitting checks the whole platform fee account (granted credit first): short, refused with every
 *      amount and nothing written; credited and topped up, it goes through; withdrawn, everything comes back
 *   2  a publication on each node (a digital prize, a prize at her place), approved by the operator and live
 *   3  Rin answers on her phone: the advanced node is hidden until the basics are lit; her map; the whole map lit
 *   4  the prizes: the digital one is shown, copied and confirmed; the other is handed over at Sora's counter by Kai
 *   5  the certificate of the basics node: the PDF, and the verify page anybody opens
 *   6a the platform's own map: its issuer is the platform
 *   6b a Sponsor of the operator lights the platform's template on the challenges tab: the credit and the way to give
 *      digital prizes arrive
 *
 * Not repeated here: the editor's keyboard (tree-graph.spec), certificates turned off and the deleted account
 * (certificates.spec), and mainland China's view of a publication with a foreign video (foreign-video.spec: the
 * end-to-end run has no address database, so that spec sets the region by parameter).
 *
 * To come with the money (the plan's table says where; no placeholder is written before the feature exists):
 *   7   F-M6: the delivery fee, by the monthly job, from the credit first (run with `runJobNow`, parameters restored)
 *   8   F-M12: the monthly bill and the operator's settlement, each after the one before it
 *
 * The verify page is cached by the browser for up to a minute, so every look at it is in a context of its own.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test.describe.serial('the first launch\'s loop', () => {
  let loop: Loop

  test('0. the people of the story', async ({ request }) => {
    test.setTimeout(STEP_TIMEOUT_MS)
    loop = await loopCast(request)
    // Sora's platform fee account can be read (the credit of step 6b lands in it).
    expect(await query(request, loop.sora.token, 'qb.sponsor.fee-account-lots', {})).toEqual([])
    const [own] = await query(request, loop.sora.token, 'qb.sponsor.operator', {})
    expect(String(own.operatorId)).toBe(loop.operator.operatorId)
  })

  test('1. Sora draws a map of two nodes and another member of her operator puts it in effect', async ({ page,
    request }) => {
    test.setTimeout(STEP_TIMEOUT_MS)
    const { sora } = loop
    const name = unique('Coffee ')
    const tree = await run(request, sora.token, 'QB_TREE_CREATE', { ownerKind: 'SPONSOR', name: { en: name } })
    const saved = await run(request, sora.token, 'QB_TREE_DRAFT_SAVE', { treeId: tree.treeId,
      baseRevision: tree.revision, name: { en: name }, nodes: [
        { key: 'basics', name: { en: 'Coffee basics' }, condition: { op: 'ALL', items: [{ passed: true }] },
          certificateNames: { en: loop.certificate } },
        { key: 'advanced', name: { en: 'Latte art' }, condition: { op: 'ALL', items: [
          { node: 'basics', beforeAnswer: true }, { passed: true }] } },
      ] })
    expect(saved.problems).toEqual([])
    const ids = saved.nodeIds as Record<string, string>
    const treeId = String(tree.treeId)
    loop.map = { treeId, name, basics: ids.basics, advanced: ids.advanced }
    const submitted = await run(request, sora.token, 'QB_TREE_SUBMIT', { treeId, baseRevision: saved.revision })
    expect(submitted.approvalRequestId, 'the map waits for the operator').toBeTruthy()

    // Not in effect yet: the operator's other member has the request, and the map has no version in effect.
    const reviewer = await reviewerFor(request, sora.token)
    const waiting = await operatorTree(request, reviewer, treeId)
    expect(waiting?.approvalRequestId).toBe(submitted.approvalRequestId)
    expect(waiting?.currentVersionId ?? null).toBeNull()
    await run(request, reviewer, 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId, decision: 'APPROVE' })
    await expect.poll(async () => (await query(request, sora.token, 'qb.sponsor.trees', {}))
      .find((row) => row.treeId === treeId)?.currentVersionId ?? null,
    { message: 'the map is in effect', timeout: 30_000 }).not.toBeNull()

    // Sora's page shows the whole map.
    await signIn(page, '/sponsor/', sora.email)
    await expect(page.getByTestId('dashboard-status')).toBeVisible()
    await openArea(page, 'publications', 'map')
    const map = page.getByTestId('tree-map').getByTestId('knowledge-map')
    await expect(map).toBeVisible()
    await expect(map.locator('[data-testid="knowledge-map-node"]')).toHaveCount(2)
    await expect(map.getByText('Coffee basics')).toBeVisible()
    await expect(map.getByText('Latte art')).toBeVisible()
    await accessibleInBoth(page, 'sponsor map in effect')
  })

  test('2\'. submitting checks the whole platform fee account: short, then credited and topped up, then given back',
    async ({ request }) => {
      test.setTimeout(STEP_TIMEOUT_MS)
      const { sora, operator } = loop
      // Her operator prices the gender rule at 100 a day (its own list); charging for the rules and granting the
      // onboarding credit are global switches, put back after.
      await run(request, operator.operatorToken, 'QB_OPERATOR_AUDIENCE_PRICES_SET', { minimumFee: 0,
        prices: [{ ruleKey: 'gender', dailyPrice: 100 }], reason: 'Closed loop' })
      const restore = await rememberParams(request, [
        { key: 'qb.audience.billing.enabled', absent: 'false' },
        { key: 'qb.fee-credit.grant.enabled', absent: 'false' },
        { key: 'qb.fee-credit.grant.basic', absent: '1000' },
      ])
      try {
        await setControlledParam(request, 'qb.audience.billing.enabled', 'true')
        // A seven-day publication for women: 700 for the rule and its tax, set aside when submitted.
        const quiz = await run(request, sora.token, 'QB_QUIZ_SAVE', { title: unique('Coffee for women '), intro: null,
          timeLimitSec: 300 })
        await run(request, sora.token, 'QB_QUESTION_SAVE', { quizId: quiz.quizId, stem: CAPITALS[0].stem, points: 1,
          options: [{ text: CAPITALS[0].right, correct: true }, { text: CAPITALS[0].wrong }] })
        const version = await run(request, sora.token, 'QB_QUIZ_PUBLISH_VERSION', { quizId: quiz.quizId })
        const start = Date.now() + 86_400_000
        const saved = await run(request, sora.token, 'QB_PUBLICATION_SAVE', { versionId: version.versionId,
          name: unique('Coffee for women '), regions: ['GLOBAL'], startAt: new Date(start).toISOString(),
          endAt: new Date(start + 7 * 86_400_000).toISOString(), passScore: 1, budgetCap: 500, maxWinners: 1000,
          tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward: 10 }] })
        const publicationId = saved.publicationId as string
        await run(request, sora.token, 'QB_PUBLICATION_AUDIENCE_SAVE', { publicationId,
          criteria: [{ key: 'gender', options: ['FEMALE'] }] })

        // Short: the wizard's look and the submission say what is set aside and what is missing; nothing is written.
        const short = await run(request, sora.token, 'QB_PUBLICATION_FUNDS_CHECK', { publicationId })
        expect(short.enough).toBe(false)
        const total = Number(short.total)
        expect(total, 'the rule and its tax').toBeGreaterThanOrEqual(700)
        expect(short.items).toEqual([expect.objectContaining({ purpose: 'AUDIENCE', amount: total })])
        const refused = await request.post('/api/processes/QB_PUBLICATION_SUBMIT/latest', {
          headers: { Authorization: `Bearer ${sora.token}` }, data: { publicationId } })
        expect(refused.status()).toBe(422)
        expect((await refused.json()).violations.map((v: { ruleCode: string }) => v.ruleCode))
          .toEqual(['QB_PUBLICATION_FUNDS'])
        expect(await query(request, sora.token, 'qb.sponsor.publication-prepayments', { publicationId })).toEqual([])

        // Her operator's lead confirms her onboarding credit (500 here) and she tops up 1,000 of cash: it goes
        // through, the granted credit spent first.
        await setControlledParam(request, 'qb.fee-credit.grant.enabled', 'true')
        await setControlledParam(request, 'qb.fee-credit.grant.basic', '500',
          { type: 'monetary', currency: 'JPY', scale: 0 })
        await run(request, operator.operatorToken, 'QB_FEE_CREDIT_ONBOARDING', { sponsorId: sora.userId })
        await fund(request, sora.token, 1000)
        expect((await run(request, sora.token, 'QB_PUBLICATION_FUNDS_CHECK', { publicationId })).enough).toBe(true)
        await run(request, sora.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
        const [held] = await query(request, sora.token, 'qb.sponsor.publication-prepayments', { publicationId })
        expect(held).toEqual(expect.objectContaining({ purpose: 'AUDIENCE', status: 'OPEN', remaining: total }))
        // The granted 500 first, then the cash for the rest.
        const [wallet] = await query(request, sora.token, 'qb.sponsor.wallet', {})
        expect(Number(wallet.prepaymentHeld)).toBe(total)
        expect(Number(wallet.feeCreditBalance), 'the granted credit is spent first').toBe(0)
        expect(Number(wallet.cashBalance)).toBe(1000 - (total - 500))

        // Withdrawn: a prepayment is not a fee, everything comes back where it came from.
        await run(request, sora.token, 'QB_PUBLICATION_WITHDRAW', { publicationId })
        const [back] = await query(request, sora.token, 'qb.sponsor.publication-prepayments', { publicationId })
        expect(back).toEqual(expect.objectContaining({ status: 'RELEASED', releaseReason: 'WITHDRAWN', remaining: 0 }))
        const [after] = await query(request, sora.token, 'qb.sponsor.wallet', {})
        expect(Number(after.prepaymentHeld)).toBe(0)
        expect(Number(after.feeCreditBalance)).toBe(500)
        expect(Number(after.cashBalance)).toBe(1000)
      } finally {
        await restore()
      }
    })

  test('2. a digital prize on the basics and a prize at her place on the advanced node, approved and live',
    async ({ page, request }) => {
      test.setTimeout(STEP_TIMEOUT_MS)
      const { sora, map } = loop
      const link = `https://prizes.example/coffee?code=${unique('LOOP-')}`
      const basicsName = unique('Coffee basics quiz ')
      const advancedName = unique('Latte art quiz ')
      // Three questions each, and no word on their order: a new quiz shuffles (A2-14).
      const basics = await boundPublicationOf(request, sora.token, map!.treeId, map!.basics, basicsName, {
        questions: 3, reward: 300, prize: { method: 'DIGITAL_PRIZE', description: 'A month of coffee talk', link } })
      const advanced = await boundPublicationOf(request, sora.token, map!.treeId, map!.advanced, advancedName, {
        questions: 3, reward: 300, prize: { method: 'OFFLINE_REDEEM', description: 'A latte at our counter' } })
      loop.basics = { publicationId: basics.publicationId, name: basicsName, link }
      loop.advanced = { publicationId: advanced.publicationId, name: advancedName }
      for (const made of [basics, advanced]) {
        const [version] = await query(request, sora.token, 'qb.sponsor.quiz-versions', { quizId: made.quizId })
        expect(version.shuffled, 'a new quiz shuffles its questions').toBe(true)
      }

      // The operator approved both: Sora was mailed, and her list has both live.
      for (const name of [basicsName, advancedName]) {
        const mail = await mailTo(request, sora.email, new RegExp(`^Your publication "${name}" is approved$`))
        expect(mail.subject).toBe(`Your publication "${name}" is approved`)
      }
      await signIn(page, '/sponsor/', sora.email)
      await expect(page.getByTestId('dashboard-status')).toBeVisible()
      await page.goto('/sponsor/publications')
      for (const made of [basics, advanced]) {
        await expect(page.locator(`[data-testid="publication-row"][data-publication="${made.publicationId}"]`))
          .toHaveAttribute('data-status', 'LIVE')
      }
      await accessibleInBoth(page, 'sponsor publications, both live')
    })

  test('3. Rin answers on her phone: the advanced node shows once the basics are lit, and the whole map is lit',
    async ({ browser, baseURL, request }) => {
      test.setTimeout(STEP_TIMEOUT_MS)
      const { rin, map, basics, advanced } = loop
      const { page, close } = await phone(browser, baseURL)
      try {
        await signIn(page, '/taker/', rin.email)
        await expect(page.getByTestId('home')).toBeVisible()

        // The advanced publication is as if it did not exist yet; the basics are there.
        await searchHome(page, advanced!.name)
        await expect(page.getByTestId('feed-card').filter({ hasText: advanced!.name })).toHaveCount(0)
        await page.goto(`/taker/p/${advanced!.publicationId}`)
        await expect(page.getByTestId('detail-not-found')).toBeVisible()

        // The map: the basics can be answered, the advanced node is locked and says what it waits for.
        await page.goto(`/taker/maps/${map!.treeId}`)
        const graph = page.getByTestId('knowledge-map')
        const basicsNode = graph.locator(`[data-testid="knowledge-map-node"][data-node-id="${map!.basics}"]`)
        const advancedNode = graph.locator(`[data-testid="knowledge-map-node"][data-node-id="${map!.advanced}"]`)
        await expect(basicsNode).toHaveAttribute('data-state', 'CAN_ANSWER')
        await expect(advancedNode).toHaveAttribute('data-state', 'LOCKED')
        await advancedNode.click()
        await expect(page.getByTestId('knowledge-map-unmet')).toContainText('Coffee basics')
        await expect(page.getByTestId('knowledge-map-startable')).toHaveCount(0)
        await accessibleInBoth(page, 'app map with a locked node')

        // From the basics node to its publication, and through it from the page.
        await basicsNode.click()
        await page.getByTestId('map-publication').filter({ hasText: basics!.name }).click()
        await expect(page.getByTestId('detail-name')).toHaveText(basics!.name)
        await expect(page.getByTestId('detail-node-state')).toHaveAttribute('data-lit', 'false')
        await page.getByTestId('detail-start').click()
        await expect(page).toHaveURL(/\/play\/[^/]+$/)
        await expect(page.getByTestId('play-progress')).toHaveText('Question 1 of 3')
        await accessibleInBoth(page, 'app play, first question')
        const first = await answerQuiz(page, 3)
        basics!.attemptId = first.attemptId
        await expect(page.getByTestId('result-reward')).toHaveAttribute('data-status', 'DIGITAL')
        await accessibleInBoth(page, 'app result, basics passed')

        // "Me", then the maps: Sora's map is in the list, one node lit; its map has the basics lit and the advanced
        // node open (the lighting follows the answering by an event).
        await takerArea(page, 'me').click()
        await page.getByTestId('account-menu-maps').click()
        await expect(page).toHaveURL(/\/taker\/maps$/)
        const item = page.locator(`[data-testid="maps-item"][data-tree="${map!.treeId}"]`)
        await expect.poll(async () => {
          await page.reload()
          return (await item.getByTestId('maps-progress').textContent({ timeout: 5_000 }).catch(() => '')) ?? ''
        }, { message: 'one node lit', timeout: 30_000 }).toContain('1 of 2 nodes lit')
        await item.click()
        await expect(basicsNode).toHaveAttribute('data-state', 'LIT')
        await expect(advancedNode).toHaveAttribute('data-state', 'CAN_ANSWER')
        await advancedNode.click()
        await expect(page.getByTestId('knowledge-map-startable')).toContainText(advanced!.name)
        await accessibleInBoth(page, 'app map, basics lit')

        // The advanced one, from its node.
        await page.getByTestId('map-publication').filter({ hasText: advanced!.name }).click()
        await expect(page.getByTestId('detail-name')).toHaveText(advanced!.name)
        await page.getByTestId('detail-start').click()
        const second = await answerQuiz(page, 3)
        advanced!.attemptId = second.attemptId
        await expect(page.getByTestId('result-reward')).toHaveAttribute('data-status', 'REDEEM')
        await page.goto(`/taker/maps/${map!.treeId}`)
        await expect.poll(async () => {
          await page.reload()
          return (await advancedNode.getAttribute('data-state', { timeout: 5_000 }).catch(() => '')) ?? ''
        }, { message: 'the whole map is lit', timeout: 30_000 }).toBe('LIT')
        await expect(basicsNode).toHaveAttribute('data-state', 'LIT')
        await accessibleInBoth(page, 'app map, all lit')
        // The same two attempts, as the server has them (the questions were each Rin's own order).
        expect(first.order.slice().sort()).toEqual(CAPITALS.map((question) => question.stem).sort())
        expect(await run(request, rin.token, 'QB_ATTEMPT_RESULT', { attemptId: first.attemptId }))
          .toMatchObject({ passed: true })
      } finally {
        await close()
      }
    })

  test('4. the digital prize is shown, copied and confirmed; the other is handed over at the counter by Kai',
    async ({ browser, baseURL, page, request }) => {
      test.setTimeout(STEP_TIMEOUT_MS)
      const { rin, kai, sora, basics, advanced } = loop
      const taker = await phone(browser, baseURL, true)
      const staff = await phone(browser, baseURL)
      try {
        // The digital prize: the result never carries it, one tap shows it, it is copied and confirmed.
        const read = await run(request, rin.token, 'QB_ATTEMPT_RESULT', { attemptId: basics!.attemptId })
        expect((read.digital as { status: string }).status).toBe('DELIVERED')
        expect(JSON.stringify(read)).not.toContain(basics!.link)
        await signIn(taker.page, '/taker/', rin.email)
        await expect(taker.page.getByTestId('home')).toBeVisible()
        await taker.page.goto(`/taker/result/${basics!.attemptId}`)
        const reward = taker.page.getByTestId('result-reward')
        await expect(reward).toHaveAttribute('data-digital-status', 'DELIVERED')
        await expect(reward).toContainText('A month of coffee talk')
        await expect(reward.getByTestId('digital-content')).toHaveCount(0)
        await reward.getByTestId('digital-show').click()
        await expect(reward.getByTestId('digital-content')).toHaveText(basics!.link)
        await reward.getByTestId('digital-copy').click()
        await expect.poll(() => taker.page.evaluate(() => navigator.clipboard.readText())).toBe(basics!.link)
        await accessibleInBoth(taker.page, 'app result, digital prize shown')
        await reward.getByTestId('digital-confirm').click()
        await expect(reward).toHaveAttribute('data-digital-status', 'CONFIRMED')

        // The other prize is to collect at Sora's place: under "To collect", and its pass can be shown.
        await taker.page.goto('/taker/history/prizes')
        const due = taker.page.getByTestId('prize-row').filter({ hasText: advanced!.name })
        await expect(due).toHaveAttribute('data-status', 'DUE')
        await expect(due).toContainText('A latte at our counter')
        await accessibleInBoth(taker.page, 'app to collect')

        // Sora makes Kai her staff member (his ID is the one "Me" shows him).
        await signIn(page, '/sponsor/', sora.email)
        await expect(page.getByTestId('dashboard-status')).toBeVisible()
        await openArea(page, 'hub', 'staff')
        await page.getByTestId('staff-id').fill(kai.userId)
        await page.getByTestId('staff-find').click()
        await expect(page.getByTestId('staff-candidate')).toContainText('Kai')
        await page.getByTestId('staff-add-confirm').click()
        await expect(page.getByTestId('staff-row').filter({ hasText: kai.userId })).toBeVisible()

        // Kai at the counter; Rin shows her pass, and Kai types the digits read out.
        await signIn(staff.page, '/taker/', kai.email)
        await expect(staff.page.getByTestId('home')).toBeVisible()
        await staff.page.goto('/taker/staff')
        await expect(staff.page.getByTestId('staff-desk')).toBeVisible()
        await accessibleInBoth(staff.page, 'app staff counter')
        await takerArea(taker.page, 'scan').click()
        await taker.page.getByTestId('pass-show').click()
        await expect(taker.page.getByTestId('pass-dialog')).toBeVisible()
        const digits = (await taker.page.getByTestId('pass-digits').textContent())!.trim()
        expect(digits).toMatch(/^\d{3} \d{3}$/)
        await accessibleInBoth(taker.page, 'app pass')
        await staff.page.getByTestId('staff-pass-digits').fill(digits)
        await staff.page.getByTestId('staff-pass-read').click()
        const prize = staff.page.getByTestId('pass-prize')
        await expect(prize).toContainText('A latte at our counter')
        await expect(prize.getByTestId('pass-prize-taker')).toHaveText('Rin')
        await accessibleInBoth(staff.page, 'app counter, prize read')
        await staff.page.getByTestId('pass-hand-over').click()
        await expect(staff.page.getByTestId('pass-next')).toBeVisible()
        await expect(staff.page.getByTestId('pass-hand-over')).toHaveCount(0)

        // Handed over: Rin's result and Sora's list agree, and the list has Kai's name.
        await taker.page.keyboard.press('Escape')
        await expect.poll(async () => {
          await taker.page.goto(`/taker/result/${advanced!.attemptId}`)
          return taker.page.getByTestId('result-reward').getAttribute('data-redeem-status')
        }, { message: 'the prize is handed over', timeout: 30_000 }).toBe('REDEEMED')
        const code = ((await run(request, rin.token, 'QB_ATTEMPT_RESULT', { attemptId: advanced!.attemptId }))
          .redeem as { code: string }).code
        await page.goto('/sponsor/prizes/redeem')
        const row = page.getByTestId('redemption-row').filter({ hasText: code })
        await expect(row).toHaveAttribute('data-status', 'REDEEMED')
        await expect(row.getByTestId('redemption-staff')).toContainText('Kai')
        await accessibleInBoth(page, 'sponsor redeemed prizes')
      } finally {
        await taker.close()
        await staff.close()
      }
    })

  test('5. the certificate of the basics node: its PDF, and a verify page anybody opens',
    async ({ browser, baseURL, request }) => {
      test.setTimeout(STEP_TIMEOUT_MS)
      const { rin, sora, certificate } = loop
      const [issued] = await certificatesOf(request, rin.token, 'QB_MY_CERTIFICATIONS', certificate)
      const key = String(issued.verifyKey)
      expect(issued).toMatchObject({ source: 'NODE', shared: true, revokedAt: null, heldUntil: null,
        issuerName: sora.company })
      const { page, close } = await phone(browser, baseURL)
      try {
        await signIn(page, '/taker/', rin.email)
        await expect(page.getByTestId('home')).toBeVisible()
        await page.goto('/taker/account/certificates')
        const item = page.getByTestId('certificate-item').filter({ hasText: certificate })
        await expect(item).toBeVisible()
        await expect(item.getByTestId('certificate-issuer')).toContainText(sora.company)
        await expect(item.getByTestId('certificate-dates')).toContainText('Lit on')
        await accessibleInBoth(page, 'app certificates')
        const downloading = page.waitForEvent('download')
        await item.getByTestId('certificate-download').click()
        const download = await downloading
        expect(download.suggestedFilename()).toMatch(/\.pdf$/i)
        expect(readFileSync(await download.path()!).subarray(0, 4).toString()).toBe('%PDF')
      } finally {
        await close()
      }

      // Anybody, signed in nowhere: valid, issued by Sora's company, the day it was lit, and no end to it.
      const verify = await verifyPage(browser, baseURL, key)
      try {
        await expect(verify.visitor.getByTestId('verify-state')).toHaveText('Valid')
        await expect(verify.visitor.getByTestId('verify-certification')).toHaveText(certificate)
        await expect(verify.visitor.getByTestId('verify-issuer')).toHaveText(sora.company)
        await expect(verify.visitor.getByTestId('verify-lit')).toBeVisible()
        await expect(verify.visitor.getByTestId('verify-held')).toHaveCount(0)
        await expect(verify.visitor.getByText(/valid until|expires/i)).toHaveCount(0)
        await accessibleInBoth(verify.visitor, 'certificate verify page')
      } finally {
        await verify.close()
      }
    })

  test('6a. the platform\'s own map: lit by Rin, its certificate is issued by the platform',
    async ({ browser, baseURL, request }) => {
      test.setTimeout(STEP_TIMEOUT_MS)
      const { rin, operator, sora } = loop
      const certificate = unique('Fair dealing certificate ')
      const platform = await platformMap(request, 'TAKER', certificate)
      // The operator's official account binds a publication to the platform's node; Rin passes it.
      const publication = await boundPublicationOf(request, operator.sponsorToken, platform.treeId, platform.node,
        unique('Fair dealing quiz '))
      const { page, close } = await phone(browser, baseURL)
      try {
        await signIn(page, '/taker/', rin.email)
        await expect(page.getByTestId('home')).toBeVisible()
        await page.goto(`/taker/maps/${platform.treeId}`)
        const node = page.locator(`[data-testid="knowledge-map-node"][data-node-id="${platform.node}"]`)
        await expect(node).toHaveAttribute('data-state', 'CAN_ANSWER')
        await pass(request, rin.token, publication.publicationId)
        await expect.poll(async () => {
          await page.reload()
          return (await node.getAttribute('data-state', { timeout: 5_000 }).catch(() => '')) ?? ''
        }, { message: 'the platform node is lit', timeout: 30_000 }).toBe('LIT')
        await accessibleInBoth(page, 'app platform map, lit')
      } finally {
        await close()
      }
      const [issued] = await certificatesOf(request, rin.token, 'QB_MY_CERTIFICATIONS', certificate)
      const issuer = String(issued.issuerName)
      expect(issuer).toMatch(/QuizBuks/i)
      expect(issuer).not.toBe(sora.company)
      const verify = await verifyPage(browser, baseURL, String(issued.verifyKey))
      try {
        await expect(verify.visitor.getByTestId('verify-state')).toHaveText('Valid')
        await expect(verify.visitor.getByTestId('verify-issuer')).toHaveText(issuer)
      } finally {
        await verify.close()
      }
    })

  test('6b. a Sponsor of the operator lights the platform\'s template on the challenges tab: credit and digital prizes',
    async ({ page, request }) => {
      test.setTimeout(STEP_TIMEOUT_MS)
      const { operator, rin } = loop
      const restore = await rememberParams(request, [
        { key: 'qb.tree.grant.max-per-node', absent: '0' },
        { key: 'qb.fee-credit.grant.enabled', absent: 'false' },
        { key: 'qb.tree.gate.digital-prize-node', absent: '' },
      ])
      try {
        await setControlledParam(request, 'qb.tree.grant.max-per-node', String(GRANT),
          { type: 'monetary', currency: 'JPY', scale: 0 })
        await setControlledParam(request, 'qb.fee-credit.grant.enabled', 'true')
        // The platform's template Sponsors light: one node, granting credit and opening digital prizes.
        const template = await platformMap(request, 'SPONSOR', unique('Sponsor skills certificate '), GRANT)
        await setControlledParam(request, 'qb.tree.gate.digital-prize-node', `${template.treeId}/${template.node}`,
          { type: 'text', maxLength: 73 })
        // The operator copies it; another member puts the copy in effect; the official account publishes on its node.
        const copy = await run(request, operator.operatorToken, 'QB_TREE_COPY', { templateTreeId: template.treeId })
        const submitted = await run(request, operator.operatorToken, 'QB_TREE_SUBMIT', { treeId: copy.treeId,
          baseRevision: copy.revision })
        await decideAsOperator(request, operator.sponsorToken, submitted.approvalRequestId, 'APPROVE')
        const reviewer = await reviewerFor(request, operator.sponsorToken)
        await expect.poll(async () => (await operatorTree(request, reviewer, String(copy.treeId)))
          ?.currentVersionId ?? null, { message: 'the copy is in effect', timeout: 30_000 }).not.toBeNull()
        const publication = await boundPublicationOf(request, operator.sponsorToken, String(copy.treeId),
          template.node, unique('First prizes '))

        // Another Sponsor of the operator; a Taker cannot start the challenge.
        const chika = await sponsorOfOperator(request, operator.operatorId, 'Chika')
        const refused = await request.post('/api/processes/QB_ATTEMPT_START/latest', {
          headers: { Authorization: `Bearer ${rin.token}`, 'Idempotency-Key': crypto.randomUUID() },
          data: { publicationId: publication.publicationId } })
        expect(refused.status()).toBe(422)

        // Chika answers it on the challenges tab, from the keyboard, and passes.
        await signIn(page, '/sponsor/', chika.email)
        await expect(page.getByTestId('dashboard-status')).toBeVisible()
        await page.goto('/sponsor/profile/challenges')
        const map = page.locator(`[data-testid="challenge-map"][data-tree="${copy.treeId}"]`)
        await expect(map).toBeVisible()
        await map.locator(`[data-testid="knowledge-map-node"][data-node-id="${template.node}"]`).click()
        await expect(map.getByTestId('knowledge-map-grant')).toBeVisible()
        await accessibleInBoth(page, 'sponsor challenges')
        await map.getByTestId('challenge-start').click()
        await expect(page.getByTestId('play-question')).toContainText('Capital of France?')
        await page.getByTestId('play-option').filter({ hasText: 'Paris' }).click()
        await page.getByTestId('play-submit').focus()
        await page.keyboard.press('Enter')
        await page.getByRole('button', { name: 'Submit answers' }).last().click()
        const result = page.getByTestId('challenge-result')
        await expect(result).toHaveAttribute('data-passed', 'true')
        await expect(result.getByTestId('challenge-grant')).toBeVisible({ timeout: 60_000 })
        await expect(result.getByTestId('challenge-gate')).toContainText('digital prizes')
        await accessibleInBoth(page, 'sponsor challenge passed')

        // The node is lit: digital prizes are open to Chika, and the credit is a lot of her platform fee account.
        await expect.poll(async () => String((await query(request, chika.token, 'qb.sponsor.reward-methods', {}))[0]
          ?.methods ?? '').split(','), { timeout: 30_000 }).toContain('DIGITAL_PRIZE')
        await expect.poll(async () => (await query(request, chika.token, 'qb.sponsor.fee-account-lots', {}))
          .some((row) => row.subAccount === 'FEE_CREDIT' && Number(row.remaining) === GRANT), { timeout: 30_000 })
          .toBe(true)

        // Another Sponsor who answers wrong is told to wait for the next challenge.
        const kei = await sponsorOfOperator(request, operator.operatorId, 'Kei')
        const started = await run(request, kei.token, 'QB_SPONSOR_ATTEMPT_START',
          { publicationId: publication.publicationId })
        const questions = started.questions as { no: number; options: { no: number; text: string }[] }[]
        await run(request, kei.token, 'QB_SPONSOR_ATTEMPT_SUBMIT', { attemptId: started.attemptId,
          publicationId: publication.publicationId, answers: questions.map((q) => ({ questionNo: q.no,
            selected: q.options.filter((o) => o.text !== 'Paris').map((o) => o.no) })) })
        await signOut(page)
        await signIn(page, '/sponsor/', kei.email)
        await expect(page.getByTestId('dashboard-status')).toBeVisible()
        await page.goto(`/sponsor/profile/challenges/results/${started.attemptId}`)
        await expect(page.getByTestId('challenge-result')).toHaveAttribute('data-passed', 'false')
        await expect(page.getByTestId('challenge-next')).toBeVisible()
      } finally {
        await restore()
      }
    })
})

/** An approved Sponsor moved to the operator (a Sponsor signs up with the default operator). */
async function sponsorOfOperator(request: Parameters<typeof approvedSponsor>[0], operatorId: string, name: string) {
  const sponsor = await approvedSponsor(request, name)
  const me = await (await request.get('/api/auth/me', { headers: { Authorization: `Bearer ${sponsor.token}` } })).json()
  const hq = await tokenOf(request, await contentAdmin(request), PASSWORD, 'admin')
  await run(request, hq, 'QB_SPONSOR_OPERATOR_ASSIGN', { userId: me.userId, operatorId, reason: 'Closed loop' })
  return { ...sponsor, token: await tokenOf(request, sponsor.email, PASSWORD, 'sponsor') }
}
