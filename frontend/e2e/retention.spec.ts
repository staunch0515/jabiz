import { expect } from '@playwright/test'
import { signIn, test } from './support'

/**
 * ROADMAP phase 14f-3: the retention report and the open-format export from the admin UI
 * (docs/design/21-audit-retention.md sections 3 and 4).
 */
test.describe('retention', () => {
  test('the report lists the policies and a dataset exports as a ZIP', async ({ page }) => {
    await signIn(page)
    await page.goto('/retention')
    await expect(page.getByTestId('retention-policies')).toContainText('FreightCharge')
    await expect(page.getByTestId('retention-expired-FreightCharge')).toBeVisible()

    await page.getByTestId('export-datasets').click()
    await page.keyboard.type('LedgerAccount')
    await page.locator('.ant-select-item-option').filter({ hasText: 'urn:jabiz:dataset:platform:LedgerAccount' })
      .first().click()
    const download = page.waitForEvent('download')
    await page.getByTestId('export-submit').click()
    expect((await download).suggestedFilename()).toMatch(/^jabiz-export-.*\.zip$/)
  })
})
