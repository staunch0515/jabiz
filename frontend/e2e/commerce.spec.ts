import { expect, test } from '@playwright/test'
import { adminToken, insert, signIn, unique } from './support'

const PRODUCTS = 'urn:jabiz:dataset:default:Product'
const WAREHOUSES = 'urn:jabiz:dataset:default:Warehouse'

/**
 * ROADMAP phase 11: the orders-and-inventory sample is declarations only (entities, datasets, processes); its list
 * page and its process form come from the metadata, with no frontend code for them.
 */
test('the commerce sample has generated pages and an order is placed from its generated form', async ({
  page,
  request,
}) => {
  const token = await adminToken(request)
  const code = unique('E')
  await insert(request, token, WAREHOUSES, { warehouseCode: code, warehouseName: `Warehouse ${code}`, active: true })
  await insert(request, token, PRODUCTS, { sku: code, productName: `Product ${code}`, unitPrice: 250, active: true })
  const received = await request.post('/api/processes/STOCK_RECEIVE/latest', {
    headers: { Authorization: `Bearer ${token}` },
    data: { warehouseCode: code, sku: code, quantity: 5 },
  })
  expect(received.status(), await received.text()).toBe(200)

  await signIn(page)
  await page.getByTestId('dataset-Product').click()
  await expect(page.getByTestId('page-title')).toHaveText('商品')
  for (const title of ['SKU', '名称', '单价', '在售']) {
    await expect(page.getByRole('columnheader', { name: title }).first()).toBeVisible()
  }
  await page.getByLabel('SKU').first().fill(code)
  await page.getByRole('button', { name: /查\s*询/ }).click()
  await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(1)
  await expect(page.locator('.ant-table-tbody tr.ant-table-row').first()).toContainText('250')

  await page.goto('/processes')
  await page.getByTestId('process-ORDER_PLACE-1').click()
  await page.getByLabel('orderNo').fill(code)
  await page.getByLabel('customerCode').fill('E2E')
  await page.getByLabel('warehouseCode').fill(code)
  await page.getByLabel('sku').fill(code)
  await page.getByLabel('quantity').fill('2')
  await page.getByRole('button', { name: /执\s*行/ }).click()
  await expect(page.getByTestId('process-result')).toContainText(code)
  await expect(page.getByTestId('process-result')).toContainText('500')
})

// Regression: date-time inputs of a process form were sent as local text without a zone, which the server rejects.
test('a price change is scheduled from the generated process form', async ({ page, request }) => {
  const token = await adminToken(request)
  const code = unique('R')
  await insert(request, token, PRODUCTS, { sku: code, productName: `Product ${code}`, unitPrice: 100, active: true })

  await signIn(page)
  await page.goto('/processes')
  await page.getByTestId('process-PRODUCT_REPRICE-1').click()
  await page.getByLabel('sku').fill(code)
  await page.getByLabel('unitPrice').fill('120')
  await page.getByLabel('effectiveTime').fill('2031-04-01 00:00:00')
  await page.getByLabel('effectiveTime').press('Enter')
  await page.getByRole('button', { name: /执\s*行/ }).click()
  await expect(page.getByTestId('process-result')).toContainText(code)
  const expected = await page.evaluate(() => new Date(2031, 3, 1, 0, 0, 0).toISOString())
  await expect(page.getByTestId('process-result')).toContainText(expected.replace('.000Z', 'Z'))
})

