import { test as base, expect, type APIRequestContext, type Page } from '@playwright/test'

/**
 * Playwright's test, failing any test during which the page reported a Content-Security-Policy violation: the server
 * sends the admin frontend's policy (docs/design/17-apps-and-branches.md section 3.2) and the frontend must work
 * within it.
 */
export const test = base.extend<{ contentSecurityPolicy: void }>({
  contentSecurityPolicy: [
    async ({ page }, use) => {
      const violations: string[] = []
      page.on('console', (message) => {
        if (message.type() === 'error' && message.text().includes('Content Security Policy')) {
          violations.push(message.text())
        }
      })
      await use()
      expect(violations, 'Content-Security-Policy violations').toEqual([])
    },
    { auto: true },
  ],
})

/** The administrator created at server start (JABIZ_BOOTSTRAP_ADMIN_*). */
export const ADMIN = {
  userName: process.env.E2E_ADMIN_USER ?? 'admin',
  password: process.env.E2E_ADMIN_PASSWORD ?? 'admin-password-1',
}

export const CARRIERS = 'urn:jabiz:dataset:default:Carrier'
export const PRICES = 'urn:jabiz:dataset:default:Price'

/** A name no earlier run used: the tables only grow (temporal entities are never deleted). */
export function unique(prefix: string): string {
  return `${prefix}${Date.now().toString(36).toUpperCase().slice(-6)}${Math.floor(Math.random() * 36 ** 2).toString(36).toUpperCase()}`
}

/** An access token of the administrator, for preparing data through the API. */
export async function adminToken(request: APIRequestContext): Promise<string> {
  const response = await request.post('/api/auth/login', { data: ADMIN })
  expect(response.status(), await response.text()).toBe(200)
  return (await response.json()).accessToken
}

export async function commit(
  request: APIRequestContext,
  token: string,
  dataset: string,
  changes: Record<string, unknown>[],
  reason?: string,
) {
  return request.post(`/api/datasets/${encodeURIComponent(dataset)}/commit`, {
    headers: { Authorization: `Bearer ${token}`, 'Accept-Language': 'zh' },
    data: { changes, reason },
  })
}

/** Inserts one entry; returns the stored instance. */
export async function insert(
  request: APIRequestContext,
  token: string,
  dataset: string,
  attributes: Record<string, unknown>,
  effectiveTime?: string,
) {
  const response = await commit(request, token, dataset, [{ action: 'INSERT', attributes, effectiveTime }])
  expect(response.status(), await response.text()).toBe(200)
  return (await response.json())[0] as { id: string; version: number; attributes: Record<string, unknown> }
}

export async function update(
  request: APIRequestContext,
  token: string,
  dataset: string,
  id: string,
  version: number,
  attributes: Record<string, unknown>,
  effectiveTime?: string,
) {
  const response = await commit(request, token, dataset, [{ action: 'UPDATE', id, version, attributes, effectiveTime }])
  expect(response.status(), await response.text()).toBe(200)
  return (await response.json())[0] as { id: string; version: number; attributes: Record<string, unknown> }
}

export async function signIn(page: Page, user = ADMIN) {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill(user.userName)
  await page.getByPlaceholder('密码').fill(user.password)
  await page.getByRole('button', { name: /登\s*录/ }).click()
  await page.waitForURL('**/data')
}

export function datasetPath(dataset: string): string {
  return `/data/${encodeURIComponent(dataset)}`
}
