import { expect, test } from '@playwright/test'
import { adminToken, confirm, dataRows, datasetPath, entityForm, insert, option, searchedRow, signIn, unique } from './support'

const SUPPLIERS = 'urn:jabiz:dataset:default:Supplier'
const CERTIFICATIONS = 'urn:jabiz:dataset:default:SupplierCertification'

/**
 * ROADMAP phase 13d: with no frontend code for it, the sample certification gets multilingual editing with a Markdown
 * preview, a reference picker, row actions from its processes and a child list under its supplier.
 */
test('a certification is written, picked for its supplier and moved on by its row actions', async ({ page, request }) => {
  const token = await adminToken(request)
  const code = unique('C').slice(0, 10)
  const supplierName = `Kanto Tea ${code}`
  await insert(request, token, SUPPLIERS, {
    supplierCode: code,
    supplierName,
    countryCode: 'JP',
    leadTimeDays: 7,
    active: true,
  })

  await signIn(page)
  await page.goto(datasetPath(CERTIFICATIONS))
  await expect(page.getByTestId('page-title')).toHaveText('供应商资质')
  await page.getByTestId('create').click()
  const drawer = entityForm(page)

  // Status and review comment are the processes' business: not offered on creation.
  await expect(drawer.getByTestId('i18n-title')).toBeVisible()
  await expect(drawer.getByLabel('状态')).toHaveCount(0)
  await expect(drawer.getByLabel('审核意见')).toHaveCount(0)
  await drawer.getByTestId('reference-select').click()
  await page.keyboard.type(code)
  await option(page, supplierName).click()

  await page.getByTestId('i18n-title-zh').fill(`有机认证 ${code}`)
  await page.getByTestId('i18n-title-tab-en').click()
  await expect(page.getByTestId('i18n-title-tab-en')).toContainText('*')
  await page.getByTestId('i18n-title-en').fill(`Organic ${code}`)
  await page.getByTestId('i18n-body-zh').fill('由**关东**认证机构颁发。')
  await expect(page.getByTestId('i18n-body').getByTestId('markdown-view').locator('strong')).toHaveText('关东')
  await drawer.getByRole('button', { name: /保\s*存/ }).click()
  await expect(page.getByText('已保存。').or(page.getByText('Saved.'))).toBeVisible()

  const row = dataRows(page).filter({ hasText: `有机认证 ${code}` })
  await expect(row).toHaveCount(1)
  // The reference column shows the supplier's name, not its key; the status its dictionary label.
  await expect(row).toContainText(supplierName)
  await expect(row).toContainText('草稿')

  await row.getByTestId('row-action-CERTIFICATION_SUBMIT').click()
  await confirm(page)
  await expect(row).toContainText('审核中')
  await expect(row.getByTestId('row-action-CERTIFICATION_SUBMIT')).toHaveCount(0)

  // An action with more input opens its form with the certification filled in and read-only.
  await row.getByTestId('row-action-CERTIFICATION_REJECT').click()
  await expect(page.getByLabel('certificationId')).not.toBeEditable()
  await page.getByLabel('comment').fill('The certificate copy is missing.')
  await page.getByRole('button', { name: /执\s*行/ }).click()
  await expect(page.getByTestId('process-result')).toContainText('REJECTED')

  // Under the supplier, its certifications are listed.
  await page.goto(datasetPath(SUPPLIERS))
  await page.getByLabel('代码').first().fill(code)
  await page.getByRole('button', { name: /查\s*询/ }).click()
  await (await searchedRow(page, code)).getByTestId('row-edit').click()
  const children = page.getByTestId('child-list-SupplierCertification')
  await expect(children).toContainText(`有机认证 ${code}`)
  await expect(children).toContainText('已驳回')

  // A child opens in a sheet of its own over the supplier's and is saved from there: Escape closes only the child,
  // saving changes only the child, and the supplier's sheet stays open with the list read again.
  const supplierForm = page.getByRole('dialog', { name: '编辑供应商', exact: true })
  await children.getByTestId('child-open').first().click()
  const child = page.getByRole('dialog', { name: '编辑供应商资质', exact: true })
  await expect(child).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(child).toHaveCount(0)
  await expect(supplierForm).toBeVisible()
  await expect(children.getByTestId('child-open').first()).toBeFocused()
  await children.getByTestId('child-open').first().click()
  await child.getByTestId('i18n-title-zh').fill(`有机认证 ${code}（更新）`)
  await child.getByRole('button', { name: /保\s*存/ }).click()
  await expect(child).toHaveCount(0)
  await expect(supplierForm).toBeVisible()
  await expect(children).toContainText(`有机认证 ${code}（更新）`)
})
