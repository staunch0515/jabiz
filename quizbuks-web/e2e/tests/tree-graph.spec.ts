import type { Page } from '@playwright/test'
import { query } from './content'
import { reviewerFor } from './operators'
import { fundedSponsor, taker } from './play'
import { boundPublication, operatorTree, pass } from './tree'
import {
  expect, expectAccessible, openArea, prepareQuizbuks, run, setAppearance, signIn, signOut, test, unique,
} from './support'

/*
 * The knowledge map's closed loop (F-S3e, docs/quizbuks/plans/impl/F-S3.md §7): a Sponsor builds a map in the editor
 * (with the keyboard), another member of its operator approves it, publications are bound to its nodes, and a Taker
 * sees the map with their states: what they cannot start yet is not offered, and the node they pass is lit. Each page
 * passes axe in both appearances. The answering and the publication wizard are play.spec's and publishing's; the
 * visibility of a bound publication is tree-visibility.spec's.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/**
 * The page as the help shows it (docs/quizbuks/help/knowledge-map.md): attached to the run's report (the CI's
 * `quizbuks-e2e-report-*`), light, in the interface's language.
 */
async function picture(page: Page, name: string) {
  await test.info().attach(`knowledge-map-${name}.png`, { body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png' })
}

/** Both appearances pass axe on the page as it is. */
async function accessibleInBoth(page: Page, name: string) {
  for (const appearance of ['light', 'dark'] as const) {
    await setAppearance(page, appearance)
    await expectAccessible(page, `${name} (${appearance})`)
  }
  await setAppearance(page, 'light')
}

test('a Sponsor builds a map, its operator approves it, and a Taker lights a node', async ({ page, request }) => {
  test.setTimeout(240_000)
  const sponsor = await fundedSponsor(request)
  const mapName = unique('Coffee ')

  // 1. The Sponsor creates the map and writes its two nodes in the editor.
  await signIn(page, '/sponsor/', sponsor.email)
  await openArea(page, 'publications', 'map')
  await page.getByTestId('tree-create').click()
  await page.getByTestId('tree-edit').click()
  const editor = page.getByTestId('tree-editor')
  await editor.getByTestId('tree-editor-name').fill(mapName)
  await editor.getByTestId('tree-editor-add').click()
  await editor.getByTestId('tree-editor-add').click()
  const nodes = editor.getByTestId('tree-editor-node')
  await expect(nodes).toHaveCount(2)
  await nodes.nth(0).getByTestId('tree-editor-node-name').fill('Coffee basics')
  await nodes.nth(1).getByTestId('tree-editor-node-name').fill('Latte art')
  // The advanced node asks for the basics lit before answering, chosen with the keyboard.
  const advanced = nodes.nth(1)
  await advanced.getByTestId('condition-add-node').focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('option', { name: 'Coffee basics' })).toBeVisible()
  await page.keyboard.press('Enter')
  await expect(advanced.getByText('“Coffee basics” lit')).toBeVisible()
  await advanced.getByTestId('condition-before-answer').focus()
  await page.keyboard.press('Space')
  await expect(advanced.getByTestId('condition-before-answer')).toBeChecked()
  await accessibleInBoth(page, 'sponsor map editor')
  await picture(page, 'sponsor-editor')
  await editor.getByTestId('tree-editor-save').click()
  await expect(editor.getByTestId('tree-editor-submit')).toBeEnabled()
  await expect(editor.getByTestId('tree-editor-node-problems')).toHaveCount(0)
  await editor.getByTestId('tree-editor-submit').click()
  await expect(editor.getByTestId('tree-editor-in-review')).toBeVisible()

  // 2. Another member of the Sponsor's operator approves it (the review page is the extension's, KnowledgeMapIT).
  const [mine] = await query(request, sponsor.token, 'qb.sponsor.trees', {})
  const treeId = String(mine.treeId)
  const reviewer = await reviewerFor(request, sponsor.token)
  const inReview = await operatorTree(request, reviewer, treeId)
  expect(inReview?.approvalRequestId, 'the map waits for its operator').toBeTruthy()
  await run(request, reviewer, 'APPROVAL_DECIDE', { requestId: inReview?.approvalRequestId, decision: 'APPROVE' })
  await expect.poll(async () => (await query(request, sponsor.token, 'qb.sponsor.trees', {}))
    .find((row) => row.treeId === treeId)?.currentVersionId ?? null,
  { message: 'the map is in effect', timeout: 30_000 }).not.toBeNull()
  await page.reload()
  await expect(page.getByTestId('tree-map').getByTestId('knowledge-map')).toBeVisible()
  await accessibleInBoth(page, 'sponsor map in effect')
  await picture(page, 'sponsor-map')

  // 3. A publication bound to each node (the wizard's node field is publishing's).
  const bindable = (await query(request, sponsor.token, 'qb.sponsor.tree-bindable-nodes', {}))
    .filter((row) => row.treeId === treeId)
  const nodeOf = (name: string) => String(bindable.find((row) => String(row.name).includes(name))?.nodeId)
  const basicsId = nodeOf('Coffee basics')
  const advancedId = nodeOf('Latte art')
  const basicsName = unique('Coffee basics quiz ')
  const advancedName = unique('Latte art quiz ')
  const basics = await boundPublication(request, sponsor.token, treeId, basicsId, basicsName)
  await boundPublication(request, sponsor.token, treeId, advancedId, advancedName)

  // 4. The Taker's map: the basics can be answered (its publication offered); the advanced node is locked and its
  // publication not offered. The nodes are walked with the keyboard.
  const me = await taker(request, 'Rin')
  await signOut(page)
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await page.goto(`/taker/maps/${treeId}`)
  const map = page.getByTestId('knowledge-map')
  const basicsNode = map.locator(`[data-testid="knowledge-map-node"][data-node-id="${basicsId}"]`)
  const advancedNode = map.locator(`[data-testid="knowledge-map-node"][data-node-id="${advancedId}"]`)
  await expect(basicsNode).toHaveAttribute('data-state', 'CAN_ANSWER')
  await expect(advancedNode).toHaveAttribute('data-state', 'LOCKED')
  await basicsNode.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('knowledge-map-startable')).toContainText(basicsName)
  await page.keyboard.press('ArrowDown')
  await expect(advancedNode).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('knowledge-map-unmet')).toContainText('Coffee basics')
  await expect(page.getByTestId('knowledge-map-startable')).toHaveCount(0)
  await expect(page.getByText(advancedName)).toHaveCount(0)
  await accessibleInBoth(page, 'taker map with a locked node')
  await picture(page, 'taker-locked')

  // 5. The basics passed: the node is lit (the lighting follows the answering), and the advanced one is offered.
  await pass(request, me.token, basics)
  await expect.poll(async () => {
    await page.reload()
    return basicsNode.getAttribute('data-state')
  }, { message: 'the basics are lit', timeout: 30_000 }).toBe('LIT')
  await expect(advancedNode).toHaveAttribute('data-state', 'CAN_ANSWER')
  await advancedNode.click()
  await expect(page.getByTestId('knowledge-map-startable')).toContainText(advancedName)
  await accessibleInBoth(page, 'taker map with a lit node')
  await picture(page, 'taker-lit')
})
