import { expect } from '@playwright/test'
import { portalCustomer, signInToPortal } from './portal-support'
import { adminToken, chooseLanguage, insert, signIn, test, unique } from './support'

/**
 * Mail preferences (docs/design/18-numbering-approvals-tasks.md section 5.6; decision D49; ROADMAP phase 16s): each
 * sign-in entry lists its own notifications by their names, and switching the interface language only offers to
 * change the language of the mail.
 */
test('a customer turns off the portal’s shipment notices on the account page', async ({ page, request }) => {
  const customer = await portalCustomer(request)
  await signInToPortal(page, customer)
  await page.getByRole('link', { name: '账户' }).click()

  const shipped = page.getByRole('switch', { name: '发货通知' })
  await expect(shipped).toBeChecked()
  await shipped.click()
  await expect(page.getByTestId('mail-preferences-state')).toContainText('发货通知：已关闭')
  await page.reload()
  await expect(page.getByRole('switch', { name: '发货通知' })).not.toBeChecked()
})

test('the administration lists no customer mail, and offers the new interface language for the mail', async ({
  page,
  request,
}) => {
  // A user of their own: the mail language is the account's, and the shared administrator's stays as it is.
  const token = await adminToken(request)
  const role = await insert(request, token, 'urn:jabiz:dataset:platform:SecRole', {
    roleCode: unique('E2E_MAIL_'),
    labels: { en: 'Mail' },
    enabled: true,
  })
  await insert(request, token, 'urn:jabiz:dataset:platform:SecRolePermission', { roleId: role.id, permission: 'price.read' })
  const userName = unique('reader').toLowerCase()
  const password = 'reader-password-1'
  const created = await request.post('/api/processes/SEC_USER_CREATE/latest', {
    headers: { Authorization: `Bearer ${token}` },
    data: { userName, displayName: userName, password, email: `${userName}@example.com` },
  })
  expect(created.status(), await created.text()).toBe(200)
  await insert(request, token, 'urn:jabiz:dataset:platform:SecUserRole', {
    userId: (await created.json()).output.userId,
    roleId: role.id,
  })

  await signIn(page, { userName, password })
  await page.goto('/account/security')
  const mail = page.getByTestId('account-section-mail')
  // The shipment notice is the portal's (customers): not among the administration's preferences.
  await expect(mail.getByTestId('mail-preferences-none')).toBeVisible()
  await expect(mail.getByRole('switch')).toHaveCount(0)
  await expect(mail.getByRole('combobox', { name: '邮件语言' })).toHaveText('English')

  await chooseLanguage(page, '日本語', 'ja')
  const offer = page.locator('[data-sonner-toast]').filter({ hasText: 'メールも日本語で受け取りますか？' })
  await expect(offer).toBeVisible()
  // Only offered: the mail stays in English until accepted.
  await expect(mail.getByRole('combobox', { name: 'メールの言語' })).toHaveText('English')
  await offer.getByRole('button', { name: 'メールにも使う' }).click()
  await expect(page.locator('[data-sonner-toast]').filter({ hasText: '今後のメールは日本語でお送りします。' }))
    .toBeVisible()
  await page.reload()
  await expect(page.getByTestId('account-section-mail').getByRole('combobox', { name: 'メールの言語' }))
    .toHaveText('日本語')
})
