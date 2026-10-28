import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { expectAccessible, setAppearance } from '@jabiz/ui/testing/playwright'
import { ADMIN, adminToken, CARRIERS, dataRows, datasetPath, dialog, entityForm, insert, pageRendered, signIn, test, unique } from './support'

const PRODUCTS = 'urn:jabiz:dataset:default:Product'
const WAREHOUSES = 'urn:jabiz:dataset:default:Warehouse'

const SUPPLIERS = 'urn:jabiz:dataset:default:Supplier'
const CERTIFICATIONS = 'urn:jabiz:dataset:default:SupplierCertification'

/*
 * WCAG 2.2 level A and AA, checked automatically with axe on the admin frontend's pages (ROADMAP phase 14m;
 * docs/design/12-frontend.md section 11), with the helpers @jabiz/ui exports for every SPA's end-to-end tests
 * (`@jabiz/ui/testing/playwright`, ROADMAP 16v-1). Each page is checked once it has rendered.
 */

/**
 * The same pages in each appearance (decision D34 item 3). In the dark appearance the shell, menus, dialogs and every
 * page are dark: all of them are built with @jabiz/ui.
 */
async function checkPagesAfterSignIn(page: Page, request: APIRequestContext, appearance: 'light' | 'dark') {
  const at = (name: string) => (appearance === 'light' ? name : `${name} (dark)`)
  const token = await adminToken(request)
  const carrier = await insert(request, token, CARRIERS, {
    carrierCode: unique('A'), carrierName: 'Alpha Access', countryCode: 'JP', creditLimit: 1000, active: true,
  })
  // A supplier for the reference picker to offer.
  const supplierCode = unique('A').slice(0, 10)
  await insert(request, token, SUPPLIERS, {
    supplierCode, supplierName: `Access Tea ${supplierCode}`, countryCode: 'JP', leadTimeDays: 7, active: true,
  })
  await signIn(page)
  await expect(page.getByTestId('dataset-Carrier')).toBeVisible()
  // Every page follows the appearance: nothing is pinned to the light tokens any more (phase 15d).
  await expect(page.locator('.light')).toHaveCount(0)
  await expectAccessible(page, at('dataset catalog'), { expect })

  await page.goto(datasetPath(CARRIERS))
  await expect(dataRows(page).first()).toBeVisible()
  await pageRendered(page)
  await expectAccessible(page, at('dataset list'), { expect })

  // The pop-ups of the list: the delete confirmation, the calendar of the point in time.
  await dataRows(page).first().getByTestId('row-delete').click()
  await expect(page.getByRole('alertdialog')).toBeVisible()
  await expectAccessible(page, at('delete confirmation'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  await page.getByRole('button', { name: '打开日历' }).first().click()
  await expect(page.getByRole('grid')).toBeVisible()
  await expectAccessible(page, at('list date picker'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('grid')).toHaveCount(0)

  // The form sheet, and its pop-ups: a dictionary's choices, the calendar of the effective time.
  await page.getByTestId('create').click()
  const form = entityForm(page)
  await expect(form.getByLabel('代码')).toBeVisible()
  await expectAccessible(page, at('create form'), { expect })
  await form.getByRole('combobox', { name: /国家/ }).click()
  await expect(page.getByRole('option').first()).toBeVisible()
  await expectAccessible(page, at('create form choice'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('option')).toHaveCount(0)
  await form.getByRole('button', { name: '打开日历' }).first().click()
  await expect(page.getByRole('grid')).toBeVisible()
  await expectAccessible(page, at('create form date picker'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('grid')).toHaveCount(0)
  await expect(form).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(form).toHaveCount(0)

  // A reference picker, its matches fetched from the server.
  await page.goto(datasetPath(CERTIFICATIONS))
  await pageRendered(page)
  await page.getByTestId('create').click()
  await entityForm(page).getByTestId('reference-select').click()
  await expect(page.getByRole('listbox').getByRole('option').first()).toBeVisible()
  await expectAccessible(page, at('reference picker'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('listbox')).toHaveCount(0)
  await page.keyboard.press('Escape')
  await expect(entityForm(page)).toHaveCount(0)

  await page.goto(`${datasetPath(CARRIERS)}/${encodeURIComponent(carrier.id)}/history`)
  await expect(page.getByText('Alpha Access').first()).toBeVisible()
  await expectAccessible(page, at('history'), { expect })

  // The pop-ups of the history page: the calendar of a date field, the operation's sheet, the revert dialog.
  await page.getByRole('button', { name: '打开日历' }).first().click()
  await expect(page.getByRole('grid')).toBeVisible()
  await expectAccessible(page, at('history date picker'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('grid')).toHaveCount(0)
  await page.getByTestId('version-1').getByRole('button', { name: /操作详情/ }).click()
  await expect(dialog(page, /操作详情/).getByTestId('operation-detail')).toBeVisible()
  await expectAccessible(page, at('operation details'), { expect })
  await page.keyboard.press('Escape')
  await expect(dialog(page, /操作详情/)).toHaveCount(0)
  await page.getByTestId('version-1').getByTestId('revert').click()
  await expect(dialog(page, /撤销操作/)).toBeVisible()
  await expectAccessible(page, at('revert dialog'), { expect })
  await page.keyboard.press('Escape')
  await expect(dialog(page, /撤销操作/)).toHaveCount(0)

  // An issued document (phase 15c-2), for the documents page below.
  const orderId = await issuedOrderConfirmation(request, token)

  // Each page with what shows it has rendered its content (not a loading or error state).
  for (const [path, name, ready] of [
    ['/processes', 'process catalog', '[data-testid="process-PRICE_ADJUST-1"]'],
    ['/processes/PRICE_ADJUST/1', 'process form', '[data-testid="process-run"]'],
    ['/processes/APPROVAL_DECIDE/1', 'process form with a required choice', '[data-testid="process-run"]'],
    ['/tasks', 'tasks', '[data-testid="tasks"], [data-testid="tasks-empty"]'],
    ['/reports', 'report catalog', '[data-testid="report-commerce.stock_availability"]'],
    ['/reports/run?id=jabiz.ledger.account_balances', 'report', '[data-testid="report-not-run"]'],
    ['/reports/run?id=commerce.stock_availability', 'report with rows', '[data-testid="report-table"] table'],
    ['/reports/archive', 'report archive', '[data-testid="report-archive"] table'],
    ['/imports', 'import catalog', '[data-testid="import-commerce.stock"]'],
    ['/imports/runs', 'import history', '[data-testid="import-runs"] table'],
    ['/audit', 'audit', '[data-testid="audit-records"] [data-slot="data-table-row"]'],
    // Documents exist by now (issued above); the review's and the seals' lists may be empty on a new database:
    // their tables have loaded once no longer busy, and an answer that failed would show an error toast (below).
    ['/documents', 'documents', '[data-testid="documents"] [data-slot="data-table-row"]'],
    ['/access-review', 'access review', '[data-testid="access-review-list"] table:not([aria-busy])'],
    ['/integrity', 'integrity', '[data-testid="integrity-checks"] table:not([aria-busy])'],
    ['/retention', 'retention', '[data-testid="export-form"]'],
    ['/account/security', 'account security', '[data-testid="account-section-mfa"] [data-testid="mfa-status"]'],
    ['/security/sessions', 'sessions', '[data-testid="session-user-select"]'],
  ] as const) {
    await page.goto(path)
    await expect(page.locator(ready).first(), `${name} rendered`).toBeVisible()
    await pageRendered(page)
    await expect(page.locator('[data-sonner-toast][data-type="error"]'), `${name}: no error`).toHaveCount(0)
    if (path === '/account/security') {
      await expect(page.getByTestId('session-item').first()).toBeVisible()
      await expect(page.getByTestId('mail-preferences')
        .locator('[data-testid="mail-preferences-none"], [data-testid="mail-preference"]').first()).toBeVisible()
    }
    await expectAccessible(page, at(name), { expect })
  }

  // The issued document: its deliveries opened under the row, and the dialog that sends it.
  await page.goto(`/documents?subject=${orderId}`)
  await expect(dataRows(page.getByTestId('documents'))).toHaveCount(1)
  await page.getByRole('button', { name: '展开行' }).first().click()
  await expect(page.locator('[data-testid^="document-deliveries-"]')).toBeVisible()
  await expectAccessible(page, at('document deliveries'), { expect })
  await page.locator('[data-testid^="document-send-"]').first().click()
  await expect(dialog(page, '以邮件发送')).toBeVisible()
  await expectAccessible(page, at('send document dialog'), { expect })
  await page.keyboard.press('Escape')
  await expect(dialog(page, '以邮件发送')).toHaveCount(0)

  // The access review's issue confirmation and, once issued, its sign-off dialog.
  await page.goto('/access-review')
  await pageRendered(page)
  await page.getByTestId('access-review-issue').click()
  await expect(page.getByRole('alertdialog')).toBeVisible()
  await expectAccessible(page, at('access report issue confirmation'), { expect })
  await page.getByRole('alertdialog').getByRole('button', { name: '签发', exact: true }).click()
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  await expect(page.getByTestId('access-review-report')).toBeVisible()
  await page.getByTestId('access-review-sign').click()
  await expect(dialog(page, '签核审查')).toBeVisible()
  await expectAccessible(page, at('access review sign-off dialog'), { expect })
  await page.keyboard.press('Escape')
  await expect(dialog(page, '签核审查')).toHaveCount(0)

  // A verification's details, in a sheet.
  await page.goto('/integrity')
  await page.getByTestId('integrity-verify').click()
  await expect(dialog(page, /#\d+/)).toBeVisible()
  await expectAccessible(page, at('verification details'), { expect })
  await page.keyboard.press('Escape')
  await expect(dialog(page, /#\d+/)).toHaveCount(0)

  // An import's details, in a sheet: a rejected attempt is recorded as well.
  const runId = await rejectedImport(request, token)
  await page.goto('/imports/runs?import=commerce.stock')
  await page.getByTestId(`run-open-${runId}`).click()
  await expect(page.getByTestId('import-run-detail')).toBeVisible()
  await expectAccessible(page, at('import run details'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByTestId('import-run-detail')).toHaveCount(0)

  // A user's sessions (phase 16g), and the dialog that ends them.
  await page.goto('/security/sessions')
  await page.getByTestId('session-user-select').click()
  await page.keyboard.type(ADMIN.userName)
  await page.getByRole('listbox').getByRole('option', { name: new RegExp(`^${ADMIN.userName}\\b`) }).first().click()
  await expect(dataRows(page).first()).toBeVisible()
  await expectAccessible(page, at('sessions of a user'), { expect })
  await page.getByTestId('admin-session-revoke-all').click()
  await expect(page.getByTestId('admin-session-reason')).toBeVisible()
  await expectAccessible(page, at('end sessions dialog'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByTestId('admin-session-reason')).toHaveCount(0)

  // The pop-ups of a report: the export menu and the issue confirmation.
  await page.goto('/reports/run?id=commerce.stock_availability')
  await expect(page.getByTestId('report-export')).toBeVisible()
  await pageRendered(page)
  await page.getByTestId('report-export').click()
  await expect(page.getByRole('menuitem').first()).toBeVisible()
  await expectAccessible(page, at('report export menu'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('menuitem')).toHaveCount(0)
  await page.getByTestId('report-issue').click()
  await expect(page.getByRole('alertdialog')).toBeVisible()
  await expectAccessible(page, at('report issue confirmation'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('alertdialog')).toHaveCount(0)

  // The import wizard at its mapping step (a file uploaded, nothing imported), with a column's choices open.
  await page.goto('/imports/run?id=commerce.stock')
  await expect(page.getByTestId('import-upload')).toBeAttached()
  await expectAccessible(page, at('import wizard'), { expect })
  await page.getByTestId('import-upload').setInputFiles({
    name: 'a11y.csv', mimeType: 'text/csv', buffer: Buffer.from('Ref,Store,Item,Count\nA11Y-1,W,S,1\n'),
  })
  await expect(page.getByTestId('import-sample')).toBeVisible()
  await expectAccessible(page, at('import mapping'), { expect })
  await page.getByRole('combobox', { name: /的来源列/ }).first().click()
  await expect(page.getByRole('option').first()).toBeVisible()
  await expectAccessible(page, at('import mapping choice'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('option')).toHaveCount(0)

  // A choice of the process form, open (a combobox's list in a popover).
  await page.goto('/processes/APPROVAL_DECIDE/1')
  await page.locator('form').getByRole('combobox').first().click()
  await expect(page.getByRole('option').first()).toBeVisible()
  await expectAccessible(page, at('process form choice'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('option')).toHaveCount(0)

  // The shell's menus, which open in portals of their own.
  await page.getByTestId('current-user').click()
  await expect(page.getByRole('menuitem').first()).toBeVisible()
  await expectAccessible(page, at('account menu'), { expect })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('menuitem')).toHaveCount(0)
  await page.getByTestId('appearance-switch').click()
  await expect(page.getByRole('menuitemradio').first()).toBeVisible()
  await expectAccessible(page, at('appearance menu'), { expect })
  await page.keyboard.press('Escape')
}

/** An order with an issued confirmation (the document lists it under its subject); the order's id. */
async function issuedOrderConfirmation(request: APIRequestContext, token: string): Promise<string> {
  const code = unique('Y')
  await insert(request, token, WAREHOUSES, { warehouseCode: code, warehouseName: `Warehouse ${code}`, active: true })
  await insert(request, token, PRODUCTS, { sku: code, productName: `Product ${code}`, unitPrice: 250, active: true })
  const headers = { Authorization: `Bearer ${token}` }
  const received = await request.post('/api/processes/STOCK_RECEIVE/latest', {
    headers, data: { warehouseCode: code, sku: code, quantity: 5 },
  })
  expect(received.status(), await received.text()).toBe(200)
  const placed = await request.post('/api/processes/ORDER_PLACE/latest', {
    headers, data: { orderNo: code, customerCode: code, warehouseCode: code, lines: [{ sku: code, quantity: 1 }] },
  })
  expect(placed.status(), await placed.text()).toBe(200)
  const orderId = (await placed.json()).output.orderId as string
  const issued = await request.post('/api/processes/ORDER_CONFIRMATION_ISSUE/latest', { headers, data: { orderId } })
  expect(issued.status(), await issued.text()).toBe(200)
  return orderId
}

/** A stock import refused for its unknown warehouse: nothing is imported, the attempt is recorded. Its run's id. */
async function rejectedImport(request: APIRequestContext, token: string): Promise<string> {
  const headers = { Authorization: `Bearer ${token}` }
  const code = unique('Z')
  const uploaded = await request.post('/api/files?policy=app.import', {
    headers,
    multipart: { file: { name: 'a11y.csv', mimeType: 'text/csv', buffer: Buffer.from(`Receipt,Warehouse,SKU,Quantity\n${code},${code},${code},1\n`) } },
  })
  expect(uploaded.status(), await uploaded.text()).toBe(201)
  const fileId = (await uploaded.json()).fileId as string
  const committed = await request.post('/api/imports/commerce.stock/commit', {
    headers,
    data: { fileId, mapping: { columns: { receipt: 'Receipt', warehouse: 'Warehouse', sku: 'SKU', quantity: 'Quantity' } } },
  })
  expect(committed.status(), await committed.text()).toBe(422)
  return (await committed.json()).report.runId as string
}

test.describe('accessibility', () => {
  for (const appearance of ['light', 'dark'] as const) {
    test(`the sign-in pages, ${appearance}`, async ({ page }) => {
      await setAppearance(page, appearance)
      await page.goto('/login')
      await expect(page.getByPlaceholder('用户名')).toBeVisible()
      await expect(page.locator('html')).toHaveClass(appearance === 'dark' ? /dark/ : /^(?!.*dark)/)
      await expectAccessible(page, `sign-in (${appearance})`, { expect })
      // With the requirements shown after an empty submission.
      await page.getByRole('button', { name: '登录', exact: true }).click()
      await expect(page.getByText('请输入用户名。')).toBeVisible()
      await expectAccessible(page, `sign-in with errors (${appearance})`, { expect })
      // A refusal from an identity provider.
      await page.goto('/login/oidc?error=access_denied')
      await expect(page.getByTestId('oidc-error')).toBeVisible()
      await expectAccessible(page, `identity provider refusal (${appearance})`, { expect })
    })
  }

  // The demo's customer portal (phase 16c-2): an application's own SPA built from the platform's blocks.
  for (const appearance of ['light', 'dark'] as const) {
    test(`the portal's sign-in, sign-up and mailed-link pages, ${appearance}`, async ({ page }) => {
      await setAppearance(page, appearance)
      await page.goto('/portal/login')
      await expect(page.getByTestId('email-link-form')).toBeVisible()
      await expect(page.getByTestId('login-user-name')).toBeVisible()
      await expect(page.locator('html')).toHaveClass(appearance === 'dark' ? /dark/ : /^(?!.*dark)/)
      await expectAccessible(page, `portal sign-in (${appearance})`, { expect })
      await page.goto('/portal/signup')
      await expect(page.getByTestId('sign-up')).toBeVisible()
      await expectAccessible(page, `portal sign-up (${appearance})`, { expect })
      await page.goto('/portal/password-reset')
      await expect(page.getByTestId('password-reset')).toBeVisible()
      await expectAccessible(page, `portal password reset (${appearance})`, { expect })
      await page.goto('/portal/auth/verify-email?entry=portal&token=not-a-token')
      await expect(page.getByTestId('verify-state')).toBeVisible()
      await expectAccessible(page, `portal mailed link (${appearance})`, { expect })
      // The portal's unsubscribe page (decision D52): a link that is no good says so.
      await page.goto('/portal/mail/unsubscribe?token=not-a-token')
      await expect(page.getByTestId('unsubscribe-state')).toHaveAttribute('data-state', 'invalid')
      await expectAccessible(page, `portal unsubscribe (${appearance})`, { expect })
    })
  }

  test('the pages after signing in', async ({ page, request }) => {
    // About thirty pages and pop-ups, each checked by axe: more than the default minute on a loaded machine.
    test.slow()
    await checkPagesAfterSignIn(page, request, 'light')
    await expect(page.locator('html')).not.toHaveClass(/dark/)
  })

  test('the pages after signing in, in the dark appearance', async ({ page, request }) => {
    test.slow()
    await setAppearance(page, 'dark')
    await checkPagesAfterSignIn(page, request, 'dark')
    await expect(page.locator('html')).toHaveClass(/dark/)
  })
})
