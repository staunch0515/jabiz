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

function phone(browser: Browser, userAgent: string) {
  return browser.newContext({ locale: 'en-US', userAgent, viewport: { width: 390, height: 844 }, hasTouch: true,
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
