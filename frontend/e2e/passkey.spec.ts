import { expect, type BrowserContext, type Page } from '@playwright/test'
import { expectAccessible } from '@jabiz/ui/testing/playwright'
import { PORTAL, portalCustomer, signInToPortal } from './portal-support'
import { test } from './support'

/**
 * Passkeys in the demo portal (decision D71; ROADMAP 17zf): Chromium's virtual authenticator stands for the phone's
 * fingerprint or face (a platform authenticator that keeps discoverable passkeys and verifies the user).
 */
async function virtualAuthenticator(context: BrowserContext, page: Page) {
  const cdp = await context.newCDPSession(page)
  await cdp.send('WebAuthn.enable')
  const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true,
      isUserVerified: true, automaticPresenceSimulation: true,
    },
  })
  // Off: the authenticator waits, as a person would before touching the sensor.
  return (present: boolean) => cdp.send('WebAuthn.setAutomaticPresenceSimulation', { authenticatorId, enabled: present })
}

test('a customer adds a passkey, signs in with it without a name, and removes it', async ({ page, context, request }) => {
  const customer = await portalCustomer(request)
  const presence = await virtualAuthenticator(context, page)
  await signInToPortal(page, customer)

  // Adding one asks for the current password: the session signed in with the password only.
  await page.getByRole('link', { name: '账户' }).click()
  await expect(page.getByTestId('passkeys-none')).toBeVisible()
  await page.getByTestId('passkey-add').click()
  await page.getByTestId('passkey-password').fill(customer.password)
  await page.getByTestId('passkey-continue').click()
  await expect(page.getByTestId('passkeys-state')).toContainText('已添加')
  await expect(page.getByTestId('passkey')).toHaveCount(1)
  await expectAccessible(page, 'portal account with a passkey', { expect })

  // Signed out, the sign-in page offers the passkey in the name field's suggestions (the virtual authenticator picks
  // it at once): two factors, straight to the orders without a name.
  await page.getByTestId('portal-sign-out').click()
  await page.waitForURL(`**${PORTAL}orders`)
  await expect(page.getByRole('heading', { name: '我的订单' })).toBeVisible()

  // The button: with nobody at the sensor the suggestions wait; the button stops them and asks.
  await presence(false)
  await page.getByTestId('portal-sign-out').click()
  await expect(page).toHaveURL(new RegExp(`${PORTAL}login$`))
  await expect(page.getByTestId('login-user-name')).toHaveAttribute('autocomplete', 'username webauthn')
  await expectAccessible(page, 'portal sign-in with passkeys', { expect })
  await presence(true)
  await page.getByTestId('login-passkey').click()
  await page.waitForURL(`**${PORTAL}orders`)

  // With a passkey, the password alone is no longer enough: the passkey completes the second step.
  await presence(false)
  await page.getByTestId('portal-sign-out').click()
  await page.getByTestId('login-user-name').fill(customer.userName)
  await page.getByPlaceholder('密码').fill(customer.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await presence(true)
  await page.getByRole('button', { name: '改用通行密钥' }).click()
  await page.waitForURL(`**${PORTAL}orders`)

  // Renamed and removed on the account page.
  await page.getByRole('link', { name: '账户' }).click()
  const passkey = page.getByTestId('passkey')
  await passkey.getByTestId('passkey-rename').click()
  await page.getByTestId('passkey-new-name').fill('E2E key')
  await page.getByTestId('passkey-save').click()
  await expect(page.getByTestId('passkey-name')).toHaveText('E2E key')
  await passkey.getByTestId('passkey-remove').click()
  await page.getByRole('alertdialog').getByRole('button', { name: '删除' }).click()
  await expect(page.getByTestId('passkeys-none')).toBeVisible()
})
