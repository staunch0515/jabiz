import { expect, type Page } from '@playwright/test'
import { adminToken, insert, signIn, test, unique } from './support'

/** The administration keeps its refresh token per tab (decision D43 item 2), under the entry's key. */
function refreshTokenOf(page: Page): Promise<string | null> {
  return page.evaluate(() => window.sessionStorage.getItem('jabiz.session.admin.refresh'))
}

/** Asks for the next tokens of a refresh token from within the page, as the client would; the status. */
function refreshStatus(page: Page, refreshToken: string | null): Promise<number> {
  return page.evaluate(async (token) => {
    const response = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: token }),
    })
    return response.status
  }, refreshToken)
}

/**
 * The session list (docs/design/10-security.md section 15.9; ROADMAP phase 16g): a user signed in on two devices
 * sees both under Security, this one marked, and signs out the other; the other's next refresh is refused at once,
 * without waiting for its access token to expire.
 */
test('a user signs out their other session, which can no longer be renewed', async ({ browser, page, request }) => {
  const token = await adminToken(request)
  const role = await insert(request, token, 'urn:jabiz:dataset:platform:SecRole', {
    roleCode: unique('E2E_SESSIONS_'),
    labels: { en: 'Sessions' },
    enabled: true,
  })
  await insert(request, token, 'urn:jabiz:dataset:platform:SecRolePermission', { roleId: role.id, permission: 'price.read' })
  const userName = unique('traveller').toLowerCase()
  const password = 'traveller-password-1'
  const created = await request.post('/api/processes/SEC_USER_CREATE/latest', {
    headers: { Authorization: `Bearer ${token}` },
    data: { userName, displayName: userName, password },
  })
  expect(created.status(), await created.text()).toBe(200)
  const userId = (await created.json()).output.userId
  await insert(request, token, 'urn:jabiz:dataset:platform:SecUserRole', { userId, roleId: role.id })

  // Another device: a browser context of its own.
  const elsewhere = await browser.newContext()
  try {
    const other = await elsewhere.newPage()
    await signIn(other, { userName, password })
    expect(await refreshTokenOf(other)).toBeTruthy()

    await signIn(page, { userName, password })
    await page.goto('/account/security')
    await expect(page.getByTestId('session-item')).toHaveCount(2)
    await expect(page.getByTestId('session-current')).toHaveCount(1)

    await page.getByTestId('session-revoke-others').click()
    await page.getByRole('alertdialog').getByRole('button', { name: '退出其他全部设备' }).click()
    await expect(page.getByTestId('session-list-state')).toContainText('已结束 1 个会话')
    await expect(page.getByTestId('session-item')).toHaveCount(1)

    // The other device's next refresh is refused now; this one's goes on.
    expect(await refreshStatus(other, await refreshTokenOf(other))).toBe(401)
    expect(await refreshStatus(page, await refreshTokenOf(page))).toBe(200)
  } finally {
    await elsewhere.close()
  }
})
