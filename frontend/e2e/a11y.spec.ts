import AxeBuilder from '@axe-core/playwright'
import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { adminToken, CARRIERS, datasetPath, insert, signIn, test, unique } from './support'

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
 * The same pages in each appearance (decision D34 item 3). In the dark appearance the shell, menus and dialogs are
 * dark; the Ant Design pages inside keep the light tokens until they move (phases 15b, 15c).
 */
async function checkPagesAfterSignIn(page: Page, request: APIRequestContext, appearance: 'light' | 'dark') {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)
  const token = await adminToken(request)
  const carrier = await insert(request, token, CARRIERS, {
    carrierCode: unique('A'), carrierName: 'Alpha Access', countryCode: 'JP', creditLimit: 1000, active: true,
  })
  await signIn(page)
  await expect(page.getByTestId('dataset-Carrier')).toBeVisible()
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

  // Each page with what shows it has rendered its content (not a loading or error state).
  for (const [path, name, ready] of [
    ['/processes', 'process catalog', '[data-testid="process-PRICE_ADJUST-1"]'],
    ['/processes/PRICE_ADJUST/1', 'process form', 'form .ant-btn-primary'],
    ['/processes/APPROVAL_DECIDE/1', 'process form with a required choice', 'form .ant-btn-primary'],
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
    await expect(page.locator('.ant-result-error, .ant-result-404, .ant-result-403')).toHaveCount(0)
    await expectAccessible(page, at(name))
  }

  // The shell's menus, which open in portals of their own.
  await page.getByTestId('current-user').click()
  await expect(page.getByRole('menu')).toBeVisible()
  await expectAccessible(page, at('account menu'))
  await page.keyboard.press('Escape')
  await page.getByTestId('appearance-switch').click()
  await expect(page.getByRole('menu')).toBeVisible()
  await expectAccessible(page, at('appearance menu'))
  await page.keyboard.press('Escape')
}

test.describe('accessibility', () => {
  test('the sign-in page', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByPlaceholder('用户名')).toBeVisible()
    await expectAccessible(page, 'sign-in')
  })

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
