import { expect } from '@playwright/test'
import { expectAccessible, setAppearance } from '@jabiz/ui/testing/playwright'
import { PORTAL, placeOrderFor, portalCustomer, signInToPortal } from './portal-support'
import { signIn, test } from './support'

/**
 * The demo's customer portal (ROADMAP phase 16c-2; decision D43 items 1 and 2): an application's own SPA on the
 * platform's packages, with sessions of the entry `portal` kept on the device, apart from the administration's.
 */
test('a customer signs in to the portal, orders, and stays signed in across reloads and tabs', async ({
  page,
  context,
  request,
}) => {
  const customer = await portalCustomer(request)
  await signInToPortal(page, customer)
  await expect(page.getByTestId('orders-empty')).toBeVisible()
  // Kept on the device (persistence device), under the portal's own key.
  const keys = await page.evaluate(() => Object.keys(window.localStorage))
  expect(keys).toContain('jabiz.session.portal.refresh')
  expect(keys).not.toContain('jabiz.session.admin.refresh')

  // Ordering needs a verified address (PORTAL_ORDER_PLACE): the portal says so and offers the link again.
  await expect(page.getByTestId('verify-email-banner')).toContainText(`${customer.userName}@example.com`)
  await page.getByRole('navigation', { name: '门户' }).getByRole('link', { name: '下单' }).click()
  await page.getByTestId('order-product').selectOption(`${customer.warehouseCode}/${customer.sku}`)
  await page.getByTestId('order-quantity').fill('3')
  await page.getByTestId('form-page-save').click()
  await expect(page.getByTestId('order-error')).toContainText('请先验证邮箱')

  // My orders: the customer's own, through the server's scope.
  const orderNo = await placeOrderFor(request, customer, 3)
  await page.getByRole('navigation', { name: '门户' }).getByRole('link', { name: '我的订单' }).click()
  await expect(page.getByTestId('order-row')).toHaveCount(1)
  await expect(page.getByTestId('order-row')).toContainText(orderNo)
  await expect(page.getByTestId('order-row')).toContainText('900')

  // A reload restores the session from the device.
  await page.reload()
  await expect(page.getByTestId('order-row')).toHaveCount(1)
  await expect(page).toHaveURL(new RegExp(`${PORTAL}orders$`))

  // A second tab shares it; both reloading at once refresh one after the other (Web Locks), so neither refresh is
  // taken for a reused token, which would end the session for both.
  const second = await context.newPage()
  await second.goto(`${PORTAL}orders`)
  await expect(second.getByTestId('order-row')).toHaveCount(1)
  await Promise.all([page.reload(), second.reload()])
  await expect(page.getByTestId('order-row')).toHaveCount(1)
  await expect(second.getByTestId('order-row')).toHaveCount(1)
  await Promise.all([page.reload(), second.reload()])
  await expect(page.getByTestId('order-row')).toHaveCount(1)
  await expect(second.getByTestId('order-row')).toHaveCount(1)

  // The administration at / has a session of its own: the portal's does not sign anybody in there, and signing in
  // there (and out again) leaves the portal's alone.
  const admin = await context.newPage()
  await admin.goto('/data')
  await expect(admin).toHaveURL(/\/login$/)
  await signIn(admin)
  await admin.close()
  await page.reload()
  await expect(page.getByTestId('order-row')).toHaveCount(1)

  // Signing out ends the session in every tab of the portal.
  await page.getByTestId('portal-sign-out').click()
  await expect(page).toHaveURL(new RegExp(`${PORTAL}login$`))
  await second.reload()
  await expect(second).toHaveURL(new RegExp(`${PORTAL}login$`))
})

test('the portal accepts its own customers only, and its mailed links open at its own address', async ({
  page,
}) => {
  // The administrator holds no customer role: the portal does not accept them (decision D36 item 1).
  await page.goto(`${PORTAL}login`)
  // A mailed link first, then the password with the address as the name (phase 17m).
  await expect(page.getByTestId('email-link-form')).toBeVisible()
  await expect(page.getByTestId('login-user-name')).toHaveAttribute('placeholder', '电子邮箱')
  await page.getByTestId('login-user-name').fill(process.env.E2E_ADMIN_USER ?? 'admin')
  await page.getByPlaceholder('密码').fill(process.env.E2E_ADMIN_PASSWORD ?? 'admin-password-1')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByTestId('login-error')).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`${PORTAL}login$`))
  // A mailed link's page opens without a session, at the portal's own address.
  await page.goto(`${PORTAL}auth/password-reset?entry=portal`)
  await expect(page.getByTestId('password-reset')).toBeVisible()
})

/**
 * The application frame on a phone (ROADMAP phase 17zj): at 360 px the page does not scroll sideways, the bottom menu
 * and the floating button do not cover each other, and the frame passes axe in both appearances.
 */
for (const appearance of ['light', 'dark'] as const) {
  test(`the portal's frame on a phone (${appearance})`, async ({ page, request }) => {
    const customer = await portalCustomer(request)
    await setAppearance(page, appearance)
    await page.setViewportSize({ width: 360, height: 740 })
    await signInToPortal(page, customer)
    await expect(page.getByTestId('orders-empty')).toBeVisible()

    const bar = page.getByRole('navigation', { name: '快捷菜单' })
    await expect(bar).toBeVisible()
    await expect(bar.getByRole('link', { name: '我的订单' })).toHaveAttribute('aria-current', 'page')
    const fab = page.getByTestId('page-fab')
    await expect(fab).toBeVisible()
    const [barBox, fabBox] = [await bar.boundingBox(), await fab.boundingBox()]
    expect(fabBox!.y + fabBox!.height).toBeLessThanOrEqual(barBox!.y)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(360)
    await expectAccessible(page, `portal orders on a phone (${appearance})`, { expect })

    await bar.getByRole('link', { name: '账户' }).click()
    await expect(page.getByRole('complementary', { name: '您的账户' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(360)
    await expectAccessible(page, `portal account on a phone (${appearance})`, { expect })
  })
}

/**
 * The timeline look on a phone (phases 17zz30 and 17zz31, 12 §13.1): the bottom menu of icons is 53 px high, the page
 * and the floating button keep clear of that height, and the button is 20 px from the side and from the bar.
 */
test("the portal's frame on a phone in the timeline look", async ({ page, request }) => {
  const customer = await portalCustomer(request)
  await page.setViewportSize({ width: 360, height: 740 })
  await signInToPortal(page, customer)
  await expect(page.getByTestId('orders-empty')).toBeVisible()
  await page.goto(`${PORTAL}?look=open`)
  await expect(page.getByTestId('orders-empty')).toBeVisible()

  const bar = page.getByRole('navigation', { name: '快捷菜单' })
  await expect(bar).toHaveAttribute('data-variant', 'icons')
  const fab = page.getByTestId('page-fab')
  await expect(fab).toBeVisible()
  const [barBox, fabBox] = [(await bar.boundingBox())!, (await fab.boundingBox())!]
  expect(barBox.height, 'the bar is 53 px high and 1 px of border').toBeCloseTo(54, 0)
  expect(fabBox.width, 'the button is 56 px across').toBeCloseTo(56, 0)
  expect(360 - (fabBox.x + fabBox.width), 'the button is 20 px from the side').toBeCloseTo(20, 0)
  expect(barBox.y - (fabBox.y + fabBox.height), 'and 20 px above the bar').toBeCloseTo(20, 0)
  // The page's end keeps clear of the lower bar: its height, its border and 16 px.
  const padding = await page.locator('[data-slot="app-shell-content"]').evaluate((element) => getComputedStyle(element).paddingBottom)
  expect(padding).toBe('70px')
  await expectAccessible(page, 'portal orders on a phone in the timeline look', { expect })
})
