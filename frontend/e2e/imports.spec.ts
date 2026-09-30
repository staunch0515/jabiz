import { expect } from '@playwright/test'
import { adminToken, insert, signIn, test, unique } from './support'

const PRODUCTS = 'urn:jabiz:dataset:default:Product'
const WAREHOUSES = 'urn:jabiz:dataset:default:Warehouse'

/**
 * ROADMAP phase 14e-2: a file is imported from the imports page - uploaded, its columns mapped, previewed (every row
 * run and rolled back) and committed - and the import appears in the history.
 */
test('a file is imported through the import wizard', async ({ page, request }) => {
  const token = await adminToken(request)
  const code = unique('M')
  await insert(request, token, WAREHOUSES, { warehouseCode: code, warehouseName: `Warehouse ${code}`, active: true })
  await insert(request, token, PRODUCTS, { sku: code, productName: `Product ${code}`, unitPrice: 100, active: true })

  await signIn(page)
  await page.getByRole('link', { name: '导入' }).first().click()
  await expect(page).toHaveURL(/\/imports$/)
  await page.getByTestId('import-commerce.stock').click()
  await expect(page.getByTestId('page-title')).toHaveText('入库单')

  // A semicolon file with a title line: the layout is adjusted and the columns mapped by hand.
  const csv = `Stock count ${code}\nRef;Store;Item;Count\n${code}-1;${code};${code};4\n${code}-2;${code};${code};6\n`
  await page.locator('input[type=file]').setInputFiles({ name: 'stock.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) })
  await expect(page.getByTestId('import-sample')).toBeVisible()
  await page.getByTestId('import-delimiter').click()
  await page.getByTitle(';', { exact: true }).click()
  await expect(page.locator('.ant-select-dropdown:visible')).toHaveCount(0)
  await page.getByTestId('import-skip').fill('1')
  await page.getByTestId('import-reread').click()
  await expect(page.getByTestId('import-sample')).toContainText(`${code}-2`)
  for (const [field, column] of [['receipt', 'Ref'], ['warehouse', 'Store'], ['sku', 'Item'], ['quantity', 'Count']]) {
    await page.getByTestId(`import-column-${field}`).click()
    await page.locator('.ant-select-dropdown:visible').getByTitle(column, { exact: true }).click()
    // The list closes (with an animation) before the next one opens.
    await expect(page.locator('.ant-select-dropdown:visible')).toHaveCount(0)
  }

  await page.getByTestId('import-preview').click()
  await expect(page.getByText('没有问题，可以导入。')).toBeVisible()
  await expect(page.getByTestId('import-total-quantity')).toHaveText(/^10/)

  await page.getByTestId('import-notes').fill('e2e count')
  await page.getByTestId('import-commit').click()
  await page.locator('.ant-popconfirm').getByRole('button', { name: /导\s*入/ }).click()
  await expect(page.getByText('文件已导入。').first()).toBeVisible()

  await page.getByTestId('import-runs-link').click()
  await expect(page).toHaveURL(/\/imports\/runs\?import=commerce\.stock$/)
  await expect(page.getByText('e2e count').first()).toBeVisible()
  const download = page.waitForEvent('download')
  await page.locator('[data-testid^="run-save-"]').first().hover()
  await page.getByText('PDF', { exact: true }).click()
  expect((await download).suggestedFilename()).toMatch(/^import-commerce\.stock-\d{8}-\d{6}\.pdf$/)
})
