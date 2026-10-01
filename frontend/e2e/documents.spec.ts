import { expect } from '@playwright/test'
import { adminToken, datasetPath, insert, signIn, test, unique } from './support'

const PRODUCTS = 'urn:jabiz:dataset:default:Product'
const WAREHOUSES = 'urn:jabiz:dataset:default:Warehouse'
const ORDERS = 'urn:jabiz:dataset:default:SalesOrder'

/**
 * ROADMAP phase 14j-1: an order confirmation is issued from the order's row (a process acting on the order), then
 * found among the issued documents, saved exactly as issued and verified against the data.
 */
test('an order confirmation is issued from its row, saved as issued and verified', async ({ page, request }) => {
  const token = await adminToken(request)
  const code = unique('D')
  await insert(request, token, WAREHOUSES, { warehouseCode: code, warehouseName: `Warehouse ${code}`, active: true })
  await insert(request, token, PRODUCTS, { sku: code, productName: `Product ${code}`, unitPrice: 250, active: true })
  const headers = { Authorization: `Bearer ${token}` }
  const received = await request.post('/api/processes/STOCK_RECEIVE/latest', {
    headers, data: { warehouseCode: code, sku: code, quantity: 5 },
  })
  expect(received.status(), await received.text()).toBe(200)
  const placed = await request.post('/api/processes/ORDER_PLACE/latest', {
    headers, data: { orderNo: code, customerCode: 'E2E', warehouseCode: code, lines: [{ sku: code, quantity: 2 }] },
  })
  expect(placed.status(), await placed.text()).toBe(200)
  const orderId = (await placed.json()).output.orderId as string

  await signIn(page)
  await page.goto(datasetPath(ORDERS))
  await page.getByLabel('订单号').first().fill(code)
  await page.getByRole('button', { name: /查\s*询/ }).click()
  await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(1)
  await page.getByTestId('row-action-ORDER_CONFIRMATION_ISSUE').first().click()
  await page.locator('.ant-popconfirm').getByRole('button', { name: /确\s*定|OK/ }).click()
  await expect(page.getByText('流程已完成。')).toBeVisible()

  await page.getByRole('link', { name: '单据' }).first().click()
  await expect(page).toHaveURL(/\/documents$/)
  await page.goto(`/documents?subject=${orderId}`)
  const row = page.locator('.ant-table-tbody tr.ant-table-row')
  await expect(row).toHaveCount(1)
  await expect(row.first()).toContainText(code)

  const download = page.waitForEvent('download')
  await page.locator('[data-testid^="document-download-"]').first().click()
  expect((await download).suggestedFilename()).toBe(`${code}.pdf`)

  await page.locator('[data-testid^="document-verify-"]').first().click()
  await expect(page.locator('[data-testid^="verdict-"]').first()).toHaveText('与数据一致')
})

/**
 * ROADMAP phase 14j-2: the send dialog offers the address the confirmation names and shows the server's answer - here,
 * where the application runs without mail, its refusal.
 */
test('sending a document offers the address it names and shows the refusal of the server', async ({ page, request }) => {
  const token = await adminToken(request)
  const code = unique('M')
  await insert(request, token, WAREHOUSES, { warehouseCode: code, warehouseName: `Warehouse ${code}`, active: true })
  await insert(request, token, PRODUCTS, { sku: code, productName: `Product ${code}`, unitPrice: 250, active: true })
  const headers = { Authorization: `Bearer ${token}` }
  expect((await request.post('/api/processes/STOCK_RECEIVE/latest', {
    headers, data: { warehouseCode: code, sku: code, quantity: 5 },
  })).status()).toBe(200)
  const placed = await request.post('/api/processes/ORDER_PLACE/latest', {
    headers, data: { orderNo: code, customerCode: code, warehouseCode: code, lines: [{ sku: code, quantity: 1 }] },
  })
  const orderId = (await placed.json()).output.orderId as string
  const issued = await request.post('/api/processes/ORDER_CONFIRMATION_ISSUE/latest', { headers, data: { orderId } })
  expect(issued.status(), await issued.text()).toBe(200)

  await signIn(page)
  await page.goto(`/documents?subject=${orderId}`)
  await page.locator('[data-testid^="document-send-"]').first().click()
  await expect(page.getByTestId('document-send-to')).toContainText(`${code.toLowerCase()}@customers.example.com`)
  await page.locator('.ant-modal').getByRole('button', { name: /发\s*送/ }).click()
  await expect(page.getByTestId('document-send-error')).toContainText('邮件未启用')
})
