import { expect } from '@playwright/test'
import { adminToken, CARRIERS, commit, datasetPath, insert, signIn, test, unique } from './support'

/**
 * Acceptance criteria 1 and 2 of ROADMAP phase 10: the generated form creates and edits a Carrier, and for the same
 * invalid input the form reports the rule code the server reports (decision D15).
 */
test.describe('the generated form', () => {
  test('creates, edits and deletes an entry', async ({ page }) => {
    const code = unique('E')
    await signIn(page)
    await page.getByTestId('dataset-Carrier').click()
    await page.getByTestId('create').click()
    const drawer = page.locator('.ant-drawer')
    await drawer.getByLabel('代码').fill(code)
    await drawer.getByLabel('名称').fill('Echo Express')
    await drawer.getByLabel('国家').click()
    await page.locator('.ant-select-item-option', { hasText: '德国' }).click()
    await drawer.getByLabel('信用额度').fill('500000')
    await drawer.getByLabel('联系邮箱').fill('ops@echo.example')
    await drawer.getByRole('switch').click()
    await drawer.getByRole('button', { name: /保\s*存/ }).click()
    await expect(drawer).toBeHidden()

    await page.getByLabel('代码').first().fill(code)
    await page.getByRole('button', { name: /查\s*询/ }).click()
    const row = page.locator('.ant-table-tbody tr.ant-table-row').first()
    await expect(row).toContainText('Echo Express')
    await expect(row).toContainText('德国')

    await row.getByTestId('row-edit').click()
    await expect(drawer.getByLabel('代码')).toBeDisabled() // immutable
    await drawer.getByLabel('名称').fill('Echo Express Ltd')
    await drawer.getByRole('button', { name: /保\s*存/ }).click()
    await expect(drawer).toBeHidden()
    await expect(row).toContainText('Echo Express Ltd')

    await row.getByTestId('row-delete').click()
    await page.locator('.ant-popconfirm').getByRole('button', { name: /确\s*定|OK/ }).click()
    await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(0)
  })

  // Regression: the effective time of the form reached the submit handler as ProForm's formatted string, not a date,
  // and scheduling from the form failed with "toISOString is not a function".
  test('schedules a change from the form', async ({ page, request }) => {
    const token = await adminToken(request)
    const code = unique('S')
    const created = await insert(request, token, CARRIERS, {
      carrierCode: code,
      carrierName: 'Sierra Shipping',
      countryCode: 'JP',
      creditLimit: 1000,
      active: true,
    })
    await signIn(page)
    await page.goto(datasetPath(CARRIERS))
    await page.getByLabel('代码').first().fill(code)
    await page.getByRole('button', { name: /查\s*询/ }).click()
    const row = page.locator('.ant-table-tbody tr.ant-table-row').first()
    await row.getByTestId('row-edit').click()
    const drawer = page.locator('.ant-drawer-content')
    await drawer.getByLabel('名称').fill('Sierra Shipping 2031')
    await drawer.getByLabel('生效时间').fill('2031-01-01 09:00:00')
    await drawer.getByLabel('生效时间').press('Enter')
    await drawer.getByRole('button', { name: /保\s*存/ }).click()
    await expect(drawer).toBeHidden()

    // Scheduled, not current: the list still shows the old name, the history has the version at that time.
    await expect(row).toContainText('Sierra Shipping')
    await expect(row).not.toContainText('2031')
    const history = await request.get(
      `/api/datasets/${encodeURIComponent(CARRIERS)}/entities/${created.id}/history`,
      { headers: { Authorization: `Bearer ${token}` } },
    )
    expect(history.status()).toBe(200)
    const versions = (await history.json()) as { effectStartTime: string; attributes: Record<string, unknown> }[]
    const scheduled = versions[versions.length - 1]
    expect(scheduled.attributes.carrierName).toBe('Sierra Shipping 2031')
    expect(new Date(scheduled.effectStartTime).getTime()).toBe(await page.evaluate(() =>
      new Date(2031, 0, 1, 9, 0, 0).getTime()))
  })

  const invalid = [
    { label: '代码', input: 'ab', field: 'carrierCode', value: 'ab', code: 'CARRIER_CODE_FORMAT' },
    { label: '代码', input: 'ABCDEFGHIJK', field: 'carrierCode', value: 'ABCDEFGHIJK', code: 'TOO_LONG' },
    { label: '名称', input: '   ', field: 'carrierName', value: '   ', code: 'CARRIER_NAME_BLANK' },
    { label: '信用额度', input: '-5', field: 'creditLimit', value: '-5', code: 'CREDIT_LIMIT_RANGE' },
    { label: '信用额度', input: '10.5', field: 'creditLimit', value: '10.5', code: 'CREDIT_LIMIT_SCALE' },
    { label: '联系邮箱', input: 'not-an-email', field: 'contactEmail', value: 'not-an-email', code: 'CONTACT_EMAIL_FORMAT' },
  ]

  for (const c of invalid) {
    test(`reports ${c.code} for ${c.field} as the server does`, async ({ page, request }) => {
      // The server's answer to the same value, sent past the form.
      const token = await adminToken(request)
      const attributes: Record<string, unknown> = {
        carrierCode: unique('V'),
        carrierName: 'Valid Name',
        countryCode: 'JP',
        creditLimit: 1000,
        active: true,
      }
      attributes[c.field] = c.value
      const response = await commit(request, token, CARRIERS, [{ action: 'INSERT', attributes }])
      expect(response.status()).toBe(400)
      const problem = await response.json()
      const serverCodes = (problem.violations as { field: string; ruleCode: string }[])
        .filter((v) => v.field === c.field)
        .map((v) => v.ruleCode)
      expect(serverCodes).toEqual([c.code])

      // The form, before anything is sent.
      await signIn(page)
      await page.getByTestId('dataset-Carrier').click()
      await page.getByTestId('create').click()
      const drawer = page.locator('.ant-drawer')
      await drawer.getByLabel(c.label).fill(c.input)
      const item = drawer.locator('.ant-form-item', { has: page.getByLabel(c.label) })
      await expect(item.locator('[data-rule-code]')).toHaveAttribute('data-rule-code', c.code)
      await expect(item.locator('[data-rule-code]')).toHaveCount(serverCodes.length)
    })
  }
})
