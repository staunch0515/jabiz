import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { ADMIN, prepareBooks, signIn } from './books'

/**
 * FIN-UI-009 (ROADMAP F11e): the finance pages checked with axe for WCAG 2.2 level A and AA, as the platform checks
 * its own (frontend/e2e/a11y.spec.ts, docs/design/12-frontend.md section 11), each once its content has rendered.
 * Automatic checks find part of what an audit finds; the external audit is the user's (docs/finance/usability.md).
 */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

const PAGES: [string, string][] = [
  ['/gl/journals', 'journal register'],
  ['/gl/journals/new', 'journal entry'],
  ['/receivables/invoices', 'invoice register'],
  ['/receivables/invoices/new', 'invoice entry'],
  ['/receivables/receipts', 'receipt register'],
  ['/receivables/receipts/new', 'receipt entry'],
  ['/payables/bills', 'bill register'],
  ['/payables/bills/new', 'bill entry'],
  ['/payables/runs', 'payment runs'],
  ['/payables/payments', 'payments'],
  ['/bank/matching', 'bank matching'],
  ['/bank/reconciliations', 'bank reconciliations'],
  ['/assets', 'asset register'],
  ['/assets/depreciation', 'depreciation'],
  ['/fx/revaluations', 'revaluation'],
  ['/close', 'close workspace'],
  ['/statements/balance-sheet', 'balance sheet'],
  ['/finance-dashboard', 'dashboard'],
  ['/audit-package', 'audit package'],
]

async function expectAccessible(page: Page, name: string) {
  const results = await new AxeBuilder({ page }).withTags(TAGS).analyze()
  const found = results.violations.map((v) => ({
    rule: v.id,
    impact: v.impact,
    nodes: v.nodes.slice(0, 5).map((n) => `${n.target.join(' ')}: ${n.failureSummary?.split('\n').slice(1).join(' ') ?? ''}`),
  }))
  expect.soft(found, `${name}: WCAG A/AA violations`).toEqual([])
}

test('FIN-UI-009: the finance pages pass the automatic accessibility checks', async ({ page, request }) => {
  test.setTimeout(240_000)
  await prepareBooks(request)
  await signIn(page, ADMIN)
  for (const [path, name] of PAGES) {
    await page.goto(path)
    await expect(page.getByTestId('page-title').or(page.locator('.ant-pro-page-container-children-container'))
      .first(), `${name} rendered`).toBeVisible()
    // The user's permissions and the page's data loaded: forms are disabled until the permissions arrive.
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.ant-spin-spinning')).toHaveCount(0)
    await expect(page.locator('.ant-result-error, .ant-result-404, .ant-result-403')).toHaveCount(0)
    await expectAccessible(page, name)
  }
})
