import type { Browser } from '@playwright/test'
import { taker } from './play'
import { expect, expectAccessible, prepareQuizbuks, signIn, test } from './support'

/*
 * Adding the app to the home screen (docs/quizbuks/plans/Q38-install.md, platform 16zj): the account page is the
 * place to install at any time. No browser installs by itself, so these show what is offered: the Share-menu guide on
 * an iPhone, and on Android the browser's own confirmation (its `beforeinstallprompt`, given by the test as Chrome
 * would give it once the app qualifies).
 */

const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1'
const ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'
/** Browsers of phones sold in China (Q50, platform 17m): they never offer their own installation. */
const XIAOMI = 'Mozilla/5.0 (Linux; U; Android 14; zh-cn; 23116PN5BC Build/UKQ1.230804.001) AppleWebKit/537.36 '
  + '(KHTML, like Gecko) Version/4.0 Chrome/118.0.5993.80 Mobile Safari/537.36 XiaoMi/MiuiBrowser/18.4.30612'
const WECHAT = 'Mozilla/5.0 (Linux; Android 14; 23116PN5BC Build/UKQ1.230804.001; wv) AppleWebKit/537.36 (KHTML, like '
  + 'Gecko) Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36 XWEB/1160117 MicroMessenger/8.0.49.2600(0x28003133) WeChat/arm64'

function phone(browser: Browser, userAgent: string, locale = 'en-US') {
  return browser.newContext({ locale, userAgent, viewport: { width: 390, height: 844 }, hasTouch: true,
    isMobile: true })
}

test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('an iPhone is guided through the Share menu from the account page', async ({ browser, request }) => {
  const { email } = await taker(request, 'Ina')
  const context = await phone(browser, IPHONE)
  const page = await context.newPage()
  await signIn(page, '/taker/', email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto('/taker/account')
  const offer = page.getByTestId('pwa-install')
  await expect(offer).toHaveAttribute('data-situation', 'ios')
  // The account page's entry stays: nothing to close.
  await expect(page.getByTestId('pwa-install-dismiss')).toHaveCount(0)
  await page.getByRole('button', { name: 'Show me how' }).click()
  await expect(page.getByTestId('pwa-install-guide')).toContainText('Add to Home Screen')
  await expect(page.getByTestId('pwa-install-sign-in')).toContainText('sign in once more')
  await expectAccessible(page, 'install guide')
  await context.close()
})

test('Android shows the browser\'s own confirmation, once', async ({ browser, request }) => {
  const { email } = await taker(request, 'Andy')
  const context = await phone(browser, ANDROID)
  const page = await context.newPage()
  await signIn(page, '/taker/', email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto('/taker/account')
  await expect(page.getByTestId('account')).toBeVisible()
  // Chrome's offer, as it fires it once the app qualifies: kept by the page, not shown as the browser's own bar.
  const prevented = await page.evaluate(() => {
    const offer = new Event('beforeinstallprompt', { cancelable: true }) as Event & Record<string, unknown>
    const shown = window as unknown as { installShown: number }
    shown.installShown = 0
    offer.prompt = async () => {
      shown.installShown++
    }
    offer.userChoice = Promise.resolve({ outcome: 'accepted' })
    window.dispatchEvent(offer)
    return offer.defaultPrevented
  })
  expect(prevented).toBe(true)
  await expect(page.getByTestId('pwa-install')).toHaveAttribute('data-situation', 'prompt')
  await page.getByTestId('pwa-install-button').click()
  await expect.poll(() => page.evaluate(() => (window as unknown as { installShown: number }).installShown)).toBe(1)
  await expect(page.getByTestId('pwa-install')).toContainText('QuizBuks is on this device\'s home screen.')
  await context.close()
})

test('a Xiaomi browser is guided through its own menu, in Chinese (Q50)', async ({ browser, request }) => {
  const { email } = await taker(request, 'Xiao')
  const context = await phone(browser, XIAOMI, 'zh-CN')
  // The real browser never offers its own installation; Chromium, under its name here, keeps quiet too.
  await context.addInitScript(() => window.addEventListener('beforeinstallprompt',
    (event) => event.stopImmediatePropagation(), true))
  const page = await context.newPage()
  await signIn(page, '/taker/', email)
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto('/taker/account')
  const offer = page.getByTestId('pwa-install')
  await expect(offer).toHaveAttribute('data-situation', 'menu')
  await offer.getByRole('button', { name: '查看步骤' }).click()
  const guide = page.getByTestId('pwa-install-guide')
  await expect(guide).toHaveAttribute('data-browser', 'xiaomi')
  await expect(guide).toContainText('工具箱')
  await expect(page.getByTestId('pwa-install-permission')).toContainText('桌面快捷方式')
  await expectAccessible(page, 'install guide of the Xiaomi browser')
  await context.close()
})

test('a share link opened in WeChat says first how to open it in the phone\'s browser (Q50)', async ({ browser }) => {
  const context = await phone(browser, WECHAT, 'zh-CN')
  const page = await context.newPage()
  await page.goto('/taker/login')
  const notice = page.getByTestId('pwa-install')
  await expect(notice).toHaveAttribute('data-situation', 'in-app')
  await expect(notice).toContainText('点右上角的"···"')
  // In Chinese the mailed link comes first, before the password and Google, Facebook and LINE.
  await expect(page.getByTestId('email-link-form')).toBeVisible()
  await expectAccessible(page, 'sign-in inside WeChat')
  await context.close()
})
