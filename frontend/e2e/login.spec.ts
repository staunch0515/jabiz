import { expect } from '@playwright/test'
import { ADMIN, adminToken, CARRIERS, datasetPath, insert, signIn, test, unique } from './support'

/** Sign-in, the dynamic menu, languages and what the UI offers by permission (ROADMAP phase 10 requirement 4). */
test.describe('sign-in and navigation', () => {
  test('a wrong password is refused with the server message', async ({ page }) => {
    await page.goto('/data')
    await expect(page).toHaveURL(/\/login$/)
    await page.getByPlaceholder('用户名').fill(ADMIN.userName)
    await page.getByPlaceholder('密码').fill('not-the-password')
    await page.getByRole('button', { name: /登\s*录/ }).click()
    await expect(page.getByTestId('login-error')).toBeVisible()
    await expect(page).toHaveURL(/\/login$/)
  })

  test('menus come from the server in the chosen language, and sign-out ends the session', async ({ page, request }) => {
    const token = await adminToken(request)
    const code = unique('E2E_MENU_')
    await insert(request, token, 'urn:jabiz:dataset:platform:SecMenu', {
      menuCode: code,
      labels: { zh: `承运商菜单${code}`, ja: `運送会社メニュー${code}`, en: `Carrier menu ${code}` },
      path: datasetPath(CARRIERS),
      sortOrder: 1,
      permission: 'logistics.carrier.read',
      enabled: true,
    })

    await signIn(page)
    await expect(page.getByRole('menuitem', { name: `承运商菜单${code}` })).toBeVisible()
    await page.getByRole('menuitem', { name: `承运商菜单${code}` }).click()
    await expect(page).toHaveURL(new RegExp(encodeURIComponent(CARRIERS)))
    await expect(page.getByTestId('page-title')).toHaveText('承运商')

    await page.getByTestId('language-switch').hover()
    await page.getByText('日本語').click()
    await expect(page.getByRole('menuitem', { name: `運送会社メニュー${code}` })).toBeVisible()
    await expect(page.getByTestId('page-title')).toHaveText('運送会社')
    await page.getByTestId('language-switch').hover()
    await page.getByText('English').click()
    await expect(page.getByRole('menuitem', { name: `Carrier menu ${code}` })).toBeVisible()

    // A reload keeps the session (the refresh token lives in sessionStorage).
    await page.reload()
    await expect(page.getByRole('menuitem', { name: `Carrier menu ${code}` })).toBeVisible()

    await page.getByTestId('current-user').hover()
    await page.getByText('Sign out').click()
    await expect(page).toHaveURL(/\/login$/)
    await page.goto(datasetPath(CARRIERS))
    await expect(page).toHaveURL(/\/login$/)
  })

  test('a user who may only read sees no write actions', async ({ page, request }) => {
    const token = await adminToken(request)
    const headers = { Authorization: `Bearer ${token}` }
    const roleCode = unique('E2E_READER_')
    const role = await insert(request, token, 'urn:jabiz:dataset:platform:SecRole', {
      roleCode,
      labels: { en: roleCode },
      enabled: true,
    })
    await insert(request, token, 'urn:jabiz:dataset:platform:SecRolePermission', {
      roleId: role.id,
      permission: 'logistics.carrier.read',
    })
    const userName = unique('reader').toLowerCase()
    const password = 'reader-password-1'
    const created = await request.post('/api/processes/SEC_USER_CREATE/latest', {
      headers,
      data: { userName, displayName: userName, password },
    })
    expect(created.status(), await created.text()).toBe(200)
    const userId = (await created.json()).output.userId
    await insert(request, token, 'urn:jabiz:dataset:platform:SecUserRole', { userId, roleId: role.id })

    await signIn(page, { userName, password })
    // Only the one dataset the role may read, and no processes.
    await expect(page.getByTestId('dataset-Carrier')).toBeVisible()
    await expect(page.getByRole('link', { name: '价格' })).toHaveCount(0)
    await page.getByTestId('dataset-Carrier').click()
    await expect(page.getByTestId('page-title')).toHaveText('承运商')
    await expect(page.getByTestId('create')).toHaveCount(0)
    await expect(page.getByTestId('row-edit')).toHaveCount(0)
    await page.goto('/processes')
    await expect(page.locator('[data-testid^="process-"]')).toHaveCount(0)
  })
})
