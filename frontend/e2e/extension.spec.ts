import { expect } from '@playwright/test'
import { adminToken, insert, signIn, test, unique } from './support'

const PRODUCTS = 'urn:jabiz:dataset:default:Product'
const WAREHOUSES = 'urn:jabiz:dataset:default:Warehouse'

/**
 * The demo application's own admin page (decision D22, backend/app/admin-extension): compiled into the admin
 * frontend, reached from the menu, reading a SQL template and running a process with the user's permissions.
 */
test('the application page lists stock and receives goods', async ({ page, request }) => {
  const token = await adminToken(request)
  const code = unique('X')
  await insert(request, token, WAREHOUSES, { warehouseCode: code, warehouseName: `Warehouse ${code}`, active: true })
  await insert(request, token, PRODUCTS, { sku: code, productName: `Product ${code}`, unitPrice: 10, active: true })

  await signIn(page)
  await page.getByRole('link', { name: '库存概览' }).click()
  await expect(page).toHaveURL(/\/commerce\/stock$/)
  await expect(page.getByTestId('page-title')).toHaveText('库存概览')

  const form = page.getByTestId('receive-form')
  await form.getByLabel('仓库').fill(code)
  await form.getByLabel('SKU').fill(code)
  await form.getByLabel('数量').fill('5')
  await form.getByRole('button', { name: /入\s*库/ }).click()
  await expect(page.getByText(`已入库：${code} 在 ${code} 现有 5`)).toBeVisible()

  await page.getByLabel('按仓库筛选').fill(code)
  await page.getByLabel('按仓库筛选').press('Enter')
  const rows = page.getByTestId('stock-table').locator('tr.ant-table-row')
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText(`Product ${code}`)
  await expect(rows.first()).toContainText('5')

  // The receipt is written once (04 section 5.4): its generated list offers viewing, never editing or deleting.
  await page.getByTestId('receipts-link').click()
  await expect(page).toHaveURL(/\/data\/urn%3Ajabiz%3Adataset%3Adefault%3AStockReceipt$/)
  await expect(page.getByTestId('row-view').first()).toBeVisible()
  await expect(page.getByTestId('row-edit')).toHaveCount(0)
  await expect(page.getByTestId('row-delete')).toHaveCount(0)
})

test('a user without the permission has no menu entry and the server refuses the page its data', async ({
  page,
  request,
}) => {
  const token = await adminToken(request)
  const headers = { Authorization: `Bearer ${token}` }
  const roleCode = unique('E2E_NOSTOCK_')
  const role = await insert(request, token, 'urn:jabiz:dataset:platform:SecRole', {
    roleCode,
    labels: { en: roleCode },
    enabled: true,
  })
  await insert(request, token, 'urn:jabiz:dataset:platform:SecRolePermission', {
    roleId: role.id,
    permission: 'logistics.carrier.read',
  })
  const userName = unique('nostock').toLowerCase()
  const password = 'nostock-password-1'
  const created = await request.post('/api/processes/SEC_USER_CREATE/latest', {
    headers,
    data: { userName, displayName: userName, password },
  })
  expect(created.status(), await created.text()).toBe(200)
  await insert(request, token, 'urn:jabiz:dataset:platform:SecUserRole', {
    userId: (await created.json()).output.userId,
    roleId: role.id,
  })

  await signIn(page, { userName, password })
  await expect(page.getByTestId('dataset-Carrier')).toBeVisible()
  await expect(page.getByRole('link', { name: '库存概览' })).toHaveCount(0)

  // Hiding is navigation only: opened directly, the page renders but the server refuses the template.
  const refused = page.waitForResponse((r) => r.url().includes('/api/queries/commerce.stock_availability'))
  await page.goto('/commerce/stock')
  expect((await refused).status()).toBe(403)
  await expect(page.getByTestId('receive-form')).toHaveCount(0)
  await expect(page.getByTestId('stock-table').locator('tr.ant-table-row')).toHaveCount(0)
})
