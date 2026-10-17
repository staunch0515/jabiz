// The browser half of an unattended Stripe test-mode run (tools/quizbuks/stripe-test-mode/run.sh run --browser …;
// docs/quizbuks/stripe-test-mode.md, "CI"): what a person otherwise does on Stripe's hosted pages.
//
//   STRIPE_TM_BROWSER_URL=<url> node browser.mjs checkout <card>   pay a Checkout page with a test card, wait to be
//                                                                  sent back
//   STRIPE_TM_BROWSER_URL=<url> node browser.mjs onboard           complete a Connect Express onboarding with Stripe's
//                                                                  test data
//
// The page's address comes in the environment, never on the command line: an onboarding link signs in to the account,
// and command lines are visible to every user of the machine. It is removed from the environment before the browser
// starts.
//
// Best effort: Stripe's pages are not an API and change without notice. When a step is not recognised the helper
// stops with a screenshot in STRIPE_TM_STATE (or the current directory) and the run fails; the person-driven run
// (without --browser) always works. Playwright comes from quizbuks-web/e2e (STRIPE_TM_PLAYWRIGHT, the directory whose
// node_modules has @playwright/test).
import { createRequire } from 'node:module'
import path from 'node:path'

const [action, card] = process.argv.slice(2)
const url = process.env.STRIPE_TM_BROWSER_URL
delete process.env.STRIPE_TM_BROWSER_URL
if (!['checkout', 'onboard'].includes(action) || !url?.startsWith('https://') || (action === 'checkout' && !card)) {
  console.error('usage: STRIPE_TM_BROWSER_URL=<https url> browser.mjs checkout <card> | onboard')
  process.exit(2)
}

const from = process.env.STRIPE_TM_PLAYWRIGHT ?? path.resolve(import.meta.dirname, '../../../quizbuks-web/e2e')
const { chromium } = createRequire(path.join(from, 'package.json'))('@playwright/test')
const shots = process.env.STRIPE_TM_STATE ?? '.'

const browser = await chromium.launch(process.env.E2E_CHROMIUM ? { executablePath: process.env.E2E_CHROMIUM } : {})
const page = await browser.newPage({ locale: 'en-US' })
page.setDefaultTimeout(30_000)

/** Fills the first visible field among the selectors, if any. */
async function fill(selectors, value) {
  for (const selector of selectors) {
    const field = page.locator(selector).first()
    if (await field.isVisible().catch(() => false)) {
      await field.fill(value)
      return true
    }
  }
  return false
}

/** Clicks the first visible button whose name matches. */
async function press(name) {
  const button = page.getByRole('button', { name }).first()
  if (await button.isVisible().catch(() => false)) {
    await button.click()
    return true
  }
  return false
}

async function checkout() {
  const home = new URL(url).origin
  await page.goto(url)
  // With several payment methods the card form is behind its accordion item.
  await page.locator('[data-testid="card-accordion-item-button"]').click({ timeout: 5_000 }).catch(() => {})
  await fill(['#email'], 'stripe-test-mode@quizbuks.test')
  await page.locator('#cardNumber').fill(card)
  await page.locator('#cardExpiry').fill('12 / 34')
  await page.locator('#cardCvc').fill('123')
  await fill(['#billingName'], 'QuizBuks Test')
  await fill(['#billingPostalCode'], '1000001')
  await page.locator('button[type="submit"], [data-testid="hosted-payment-submit-button"]').first().click()
  // Paid: Stripe sends the browser back to QuizBuks's sponsor console (QB_SPONSOR_LINK).
  await page.waitForURL((u) => u.origin !== home, { timeout: 120_000 })
}

async function onboard() {
  await page.goto(url)
  // Stripe's test-mode onboarding offers test data at each page; the run ends when Stripe sends the browser back to
  // the app (QB_APP_LINK …/wallet/payout?onboarding=done).
  const deadline = Date.now() + 8 * 60_000
  while (Date.now() < deadline) {
    if (/onboarding=done/.test(page.url())) return
    if (/onboarding=refresh/.test(page.url())) throw new Error('Stripe sent the browser to the refresh address')
    let acted = false
    acted ||= await press(/use test (phone|data|account|document)|use test/i)
    acted ||= await fill(['input[name="otp"]', 'input[autocomplete="one-time-code"]', 'input[inputmode="numeric"]'],
      '000000')
    acted ||= await press(/skip this form|use test data/i)
    acted ||= await press(/^(continue|agree and submit|submit|done|save|next)/i)
    if (!acted) await page.waitForTimeout(2_000)
    await page.waitForLoadState('domcontentloaded').catch(() => {})
  }
  throw new Error('the onboarding did not finish in 8 minutes')
}

try {
  await (action === 'checkout' ? checkout() : onboard())
  console.log(`browser: ${action} done`)
} catch (error) {
  const shot = path.join(shots, `browser-${action}-${Date.now()}.png`)
  await page.screenshot({ path: shot, fullPage: true }).catch(() => {})
  console.error(`browser: ${action} failed (${error.message}); screenshot ${shot}`)
  process.exitCode = 1
} finally {
  await browser.close()
}
