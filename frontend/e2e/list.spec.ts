import { expect, test } from '@playwright/test'
import { adminToken, CARRIERS, datasetPath, insert, signIn, unique } from './support'

/**
 * Acceptance criterion 1 of ROADMAP phase 10: Carrier was added as an entity definition and a dataset only; its list
 * page (columns, search, sorting, paging) comes from the metadata without any frontend code for it.
 */
test('the list page of a new entity is generated from its metadata', async ({ page, request }) => {
  const token = await adminToken(request)
  const prefix = unique('L')
  for (const [suffix, name, limit] of [
    ['A', 'Alpha Line', 3000],
    ['B', 'Bravo Freight', 1000],
    ['C', 'Charlie Cargo', 2000],
  ] as const) {
    await insert(request, token, CARRIERS, {
      carrierCode: `${prefix}${suffix}`,
      carrierName: `${name} ${prefix}`,
      countryCode: 'JP',
      creditLimit: limit,
      active: true,
    })
  }

  await signIn(page)
  await page.getByTestId('dataset-Carrier').click()
  await expect(page.getByTestId('page-title')).toHaveText('承运商')
  // Column titles are the field labels from the server's message resources.
  for (const title of ['代码', '名称', '国家', '信用额度', '启用', '生效时间']) {
    await expect(page.getByRole('columnheader', { name: title }).first()).toBeVisible()
  }

  // Search by code (the list view whitelists it; text allows LIKE).
  await page.getByLabel('代码').first().fill(prefix)
  await page.getByRole('button', { name: /查\s*询/ }).click()
  const rows = page.locator('.ant-table-tbody tr.ant-table-row')
  await expect(rows).toHaveCount(3)
  // Default sort of the list view: code ascending. Dictionary codes show their labels, amounts their currency.
  await expect(rows.nth(0)).toContainText(`${prefix}A`)
  await expect(rows.nth(0)).toContainText('日本')
  await expect(rows.nth(0)).toContainText('3,000')

  // Sorting by credit limit (whitelisted).
  await page.getByRole('columnheader', { name: '信用额度' }).click()
  await expect(rows.nth(0)).toContainText(`${prefix}B`)
  await page.getByRole('columnheader', { name: '信用额度' }).click()
  await expect(rows.nth(0)).toContainText(`${prefix}A`)

  // A range search on the amount.
  await page.goto(datasetPath(CARRIERS))
  await page.getByLabel('代码').first().fill(prefix)
  const range = page.locator('.ant-form-item', { hasText: '信用额度' }).getByRole('spinbutton')
  await range.nth(0).fill('1500')
  await range.nth(1).fill('2500')
  await page.getByRole('button', { name: /查\s*询/ }).click()
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText(`${prefix}C`)
})
