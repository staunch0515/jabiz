import { expect, type Page } from '@playwright/test'
import { createHmac } from 'node:crypto'
import { adminToken, insert, PRICES, signIn, test, unique } from './support'

/** RFC 6238 with the authenticator defaults (SHA-1, 6 digits, 30 s), as the server checks it. */
function totp(secret: string, time = Date.now()): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = ''
  for (const char of secret.replace(/=+$/, '')) bits += alphabet.indexOf(char).toString(2).padStart(5, '0')
  const key = Buffer.from(bits.match(/.{8}/g)!.map((byte) => parseInt(byte, 2)))
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(time / 30_000)))
  const hash = createHmac('sha1', key).update(counter).digest()
  const offset = hash[hash.length - 1] & 0x0f
  const binary = (hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000
  return binary.toString().padStart(6, '0')
}

async function secretOn(page: Page): Promise<string> {
  return ((await page.getByTestId('mfa-secret').textContent()) ?? '').trim()
}

/**
 * Two-step verification (docs/design/10-security.md sections 9 and 10; ROADMAP phase 14g-1): a user sets it up, is
 * asked for a code by an operation that requires one, and signs in with a recovery code.
 */
test('a user sets up two-step verification, steps up for a price change and signs in with a recovery code', async ({
  page,
  request,
}) => {
  const token = await adminToken(request)
  const headers = { Authorization: `Bearer ${token}` }
  const role = await insert(request, token, 'urn:jabiz:dataset:platform:SecRole', {
    roleCode: unique('E2E_PRICER_'),
    labels: { en: 'Pricer' },
    enabled: true,
  })
  await insert(request, token, 'urn:jabiz:dataset:platform:SecRolePermission', { roleId: role.id, permission: 'price.adjust' })
  const userName = unique('pricer').toLowerCase()
  const password = 'pricer-password-1'
  const created = await request.post('/api/processes/SEC_USER_CREATE/latest', {
    headers,
    data: { userName, displayName: userName, password },
  })
  expect(created.status(), await created.text()).toBe(200)
  const userId = (await created.json()).output.userId
  await insert(request, token, 'urn:jabiz:dataset:platform:SecUserRole', { userId, roleId: role.id })
  const price = await insert(request, token, PRICES, { sku: unique('SKU'), amount: 100 })

  // Set up under Security: the key, a code, the recovery codes.
  await signIn(page, { userName, password })
  await page.goto('/account/security')
  await page.getByTestId('mfa-start').click()
  const secret = await secretOn(page)
  expect(secret).toMatch(/^[A-Z2-7]{32}$/)
  await page.getByTestId('mfa-code').fill(totp(secret))
  await page.getByTestId('mfa-confirm').click()
  await expect(page.getByTestId('mfa-recovery-code')).toHaveCount(10)
  const recovery = ((await page.getByTestId('mfa-recovery-code').first().textContent()) ?? '').trim()
  await page.getByTestId('mfa-saved').click()
  await expect(page.getByTestId('mfa-status')).toBeVisible()

  // The session was opened with the password only: a price change asks for a code first.
  await page.goto('/processes/PRICE_ADJUST/1')
  await page.getByLabel('priceId').fill(String(price.id))
  await page.getByLabel('version').fill(String(price.version))
  await page.getByLabel('percent').fill('10')
  await page.getByRole('button', { name: /执\s*行/ }).click()
  // The code of the next step (within the allowed drift): the confirming code does not work twice.
  await page.getByTestId('step-up-code').fill(totp(secret, Date.now() + 30_000))
  await page.locator('#step-up-submit').click()
  await expect(page.getByTestId('process-result')).toContainText('110')

  // A recovery code instead of a code at sign-in, once.
  await expect(async () => {
    await page.getByTestId('current-user').hover()
    await page.getByRole('menuitem', { name: '退出登录' }).click({ timeout: 2_000 })
    await expect(page).toHaveURL(/\/login$/, { timeout: 1_000 })
  }).toPass({ timeout: 15_000 })
  await page.getByPlaceholder('用户名').fill(userName)
  await page.getByPlaceholder('密码').fill(password)
  await page.getByRole('button', { name: /登\s*录/ }).click()
  await page.getByPlaceholder('验证码').fill(recovery)
  await page.getByRole('button', { name: /验\s*证/ }).click()
  await expect(page).toHaveURL(/\/data$/)
})
