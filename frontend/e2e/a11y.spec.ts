import AxeBuilder from '@axe-core/playwright'
import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { adminToken, CARRIERS, datasetPath, dialog, insert, pageRendered, signIn, test, unique } from './support'

/**
 * WCAG 2.2 level A and AA, checked automatically with axe on the admin frontend's pages (ROADMAP phase 14m;
 * docs/design/12-frontend.md section 11). Automatic checks find about a third of the failures an audit finds: they
 * do not replace one, they keep what they can see from coming back. Each page is checked once it has rendered.
 */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

async function expectAccessible(page: Page, name: string) {
  const results = await new AxeBuilder({ page }).withTags(TAGS).analyze()
  const found = results.violations.map((v) => ({
    rule: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.slice(0, 5).map((n) => `${n.target.join(' ')}: ${n.failureSummary?.split('\n').slice(1).join(' ') ?? ''}`),
  }))
  expect.soft(found, `${name}: WCAG A/AA violations`).toEqual([])
}

/**
 * The same pages in each appearance (decision D34 item 3). In the dark appearance the shell, menus, dialogs and the
 * pages built with @jabiz/ui are dark; the Ant Design pages keep the light tokens until they move (phases 15b–15c).
 */
async function checkPagesAfterSignIn(page: Page, request: APIRequestContext, appearance: 'light' | 'dark') {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)
  const token = await adminToken(request)
  const carrier = await insert(request, token, CARRIERS, {
    carrierCode: unique('A'), carrierName: 'Alpha Access', countryCode: 'JP', creditLimit: 1000, active: true,
  })
  await signIn(page)
  await expect(page.getByTestId('dataset-Carrier')).toBeVisible()
  // A page built with @jabiz/ui follows the appearance (phase 15b).
  await expect(page.locator('[data-slot="app-shell-content"]')).not.toHaveClass(/(^|\s)light(\s|$)/)
  await expectAccessible(page, at('dataset catalog'))

  await page.goto(datasetPath(CARRIERS))
  await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toBeVisible()
  await expectAccessible(page, at('dataset list'))

  await page.getByTestId('create').click()
  await expect(page.locator('.ant-drawer').getByLabel('代码')).toBeVisible()
  await expectAccessible(page, at('create form'))
  await page.keyboard.press('Escape')

  await page.goto(`${datasetPath(CARRIERS)}/${encodeURIComponent(carrier.id)}/history`)
  await expect(page.getByText('Alpha Access').first()).toBeVisible()
  await expectAccessible(page, at('history'))

  // The pop-ups of the history page: the calendar of a date field, the operation's sheet, the revert dialog.
  await page.getByRole('button', { name: '打开日历' }).first().click()
  await expect(page.getByRole('grid')).toBeVisible()
  await expectAccessible(page, at('history date picker'))
  await page.keyboard.press('Escape')
  await expect(page.getByRole('grid')).toHaveCount(0)
  await page.getByTestId('version-1').getByRole('button', { name: /操作详情/ }).click()
  await expect(dialog(page, /操作详情/).getByTestId('operation-detail')).toBeVisible()
  await expectAccessible(page, at('operation details'))
  await page.keyboard.press('Escape')
  await expect(dialog(page, /操作详情/)).toHaveCount(0)
  await page.getByTestId('version-1').getByTestId('revert').click()
  await expect(dialog(page, /撤销操作/)).toBeVisible()
  await expectAccessible(page, at('revert dialog'))
  await page.keyboard.press('Escape')
  await expect(dialog(page, /撤销操作/)).toHaveCount(0)

  // Each page with what shows it has rendered its content (not a loading or error state).
  for (const [path, name, ready] of [
    ['/processes', 'process catalog', '[data-testid="process-PRICE_ADJUST-1"]'],
    ['/processes/PRICE_ADJUST/1', 'process form', '[data-testid="process-run"]'],
    ['/processes/APPROVAL_DECIDE/1', 'process form with a required choice', '[data-testid="process-run"]'],
    ['/tasks', 'tasks', '.ant-list, .ant-empty'],
    ['/reports', 'report catalog', '.ant-list-item'],
    ['/reports/run?id=jabiz.ledger.account_balances', 'report', '[data-testid="page-title"]'],
    ['/imports', 'import catalog', '.ant-list-item, .ant-empty'],
    ['/audit', 'audit', '[data-testid="audit-records"] .ant-table-row'],
    ['/integrity', 'integrity', '[data-testid="integrity-verify"]'],
    ['/retention', 'retention', '[data-testid="export-form"]'],
    ['/account/security', 'account security', '.ant-card'],
  ] as const) {
    await page.goto(path)
    await expect(page.locator(ready).first(), `${name} rendered`).toBeVisible()
    await expect(page.locator('.ant-spin-spinning')).toHaveCount(0)
    await pageRendered(page)
    await expect(page.locator('.ant-result-error, .ant-result-404, .ant-result-403')).toHaveCount(0)
    await expectAccessible(page, at(name))
  }

  // A choice of the process form, open (a combobox's list in a popover).
  await page.goto('/processes/APPROVAL_DECIDE/1')
  await page.locator('form').getByRole('combobox').first().click()
  await expect(page.getByRole('option').first()).toBeVisible()
  await expectAccessible(page, at('process form choice'))
  await page.keyboard.press('Escape')
  await expect(page.getByRole('option')).toHaveCount(0)

  // The shell's menus, which open in portals of their own.
  await page.getByTestId('current-user').click()
  await expect(page.getByRole('menuitem').first()).toBeVisible()
  await expectAccessible(page, at('account menu'))
  await page.keyboard.press('Escape')
  await expect(page.getByRole('menuitem')).toHaveCount(0)
  await page.getByTestId('appearance-switch').click()
  await expect(page.getByRole('menuitemradio').first()).toBeVisible()
  await expectAccessible(page, at('appearance menu'))
  await page.keyboard.press('Escape')
}

test.describe('accessibility', () => {
  for (const appearance of ['light', 'dark'] as const) {
    test(`the sign-in pages, ${appearance}`, async ({ page }) => {
      await page.addInitScript((value) => window.localStorage.setItem('jabiz.appearance', value), appearance)
      await page.goto('/login')
      await expect(page.getByPlaceholder('用户名')).toBeVisible()
      await expect(page.locator('html')).toHaveClass(appearance === 'dark' ? /dark/ : /^(?!.*dark)/)
      await expectAccessible(page, `sign-in (${appearance})`)
      // With the requirements shown after an empty submission.
      await page.getByRole('button', { name: /登\s*录/ }).click()
      await expect(page.getByText('请输入用户名。')).toBeVisible()
      await expectAccessible(page, `sign-in with errors (${appearance})`)
      // A refusal from an identity provider.
      await page.goto('/login/oidc?error=access_denied')
      await expect(page.getByTestId('oidc-error')).toBeVisible()
      await expectAccessible(page, `identity provider refusal (${appearance})`)
    })
  }

  test('the pages after signing in', async ({ page, request }) => {
    await checkPagesAfterSignIn(page, request, 'light')
    await expect(page.locator('html')).not.toHaveClass(/dark/)
  })

  test('the pages after signing in, in the dark appearance', async ({ page, request }) => {
    await page.addInitScript(() => window.localStorage.setItem('jabiz.appearance', 'dark'))
    await checkPagesAfterSignIn(page, request, 'dark')
    await expect(page.locator('html')).toHaveClass(/dark/)
  })
})
