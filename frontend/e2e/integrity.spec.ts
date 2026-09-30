import { expect } from '@playwright/test'
import { adminToken, CARRIERS, insert, signIn, test, unique } from './support'

/**
 * ROADMAP phase 14f-2: the seals of the append-only tables can be looked at and verified from the admin UI
 * (docs/design/21-audit-retention.md section 2.4).
 */
test.describe('integrity', () => {
  test('seal, see the head and verify', async ({ page, request }) => {
    const token = await adminToken(request)
    await insert(request, token, CARRIERS, {
      carrierCode: unique('IN'), carrierName: 'Sealed', countryCode: 'JP', creditLimit: 1, active: true,
    })
    const sealed = await request.post('/api/processes/INTEGRITY_SEAL/latest', {
      headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': unique('seal') },
      data: {},
    })
    expect(sealed.ok()).toBeTruthy()
    const sealNo = (await sealed.json()).output.sealNo as number
    expect(sealNo).toBeGreaterThan(0)

    await signIn(page)
    await page.goto('/integrity')
    await expect(page.getByTestId('integrity-head')).toBeVisible()
    await expect(page.getByTestId('integrity-head-hash')).toContainText(/[0-9a-f]{64}/)

    await page.getByTestId('integrity-verify').click()
    // The run appears with its outcome and opens its details.
    await expect(page.getByTestId('integrity-checks').locator('[data-testid^="integrity-outcome-"]').first())
      .toBeVisible()
    await expect(page.locator('.ant-drawer-title')).toContainText('#')
  })
})
