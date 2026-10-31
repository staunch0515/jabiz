import { animationsSettled } from '@jabiz/ui/testing/playwright'
import { webkit, type APIRequestContext, type Browser, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { deflateSync } from 'node:zlib'
import { untilLive } from '../tests/play'
import { contentAdmin } from '../tests/legal'
import { adminToken, expect, PASSWORD, prepareQuizbuks, rows, run, signIn, test, tokenOf } from '../tests/support'
import { approvedSponsor, takerInJapan } from '../tests/wallet'
import { CONTENT, FEATURE, type Language, type StoreQuiz } from './content'

/**
 * The store's screenshots of the takers' app (docs/quizbuks/plans/Q49d-store.md): for each language a sponsor of that
 * language publishes three quizzes rewarded with a prize at the sponsor's shop, as in the launch phase (operations.md
 * "阶段一": OFFLINE_REDEEM only, no money; Q28); then for each store's phone a new taker answers one of them through
 * the API (a history to show) and another in the phone, which is photographed page by page: Play's Android phone in
 * Chromium as the Android store app (`?shell=android`, Q49b) and the App Store's 6.9-inch iPhone in WebKit as the iOS
 * app (`?shell=ios`, Q49c), so no install offer in the pictures. Each language's publications are paused afterwards, so the next language's
 * home shows only its own quizzes. Not part of the e2e suite (store.config.ts); the QuizBuks store workflow runs it
 * and keeps the pictures as its artifact, which are never committed.
 */

const OUT = process.env.STORE_OUT ?? 'store-screenshots'
const ANDROID_CHROME = 'Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36'
/** The iOS app's web view: an iPhone's WebKit with the shell's mark (capacitor.config.json `ios.appendUserAgent`). */
const IPHONE_APP = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 QuizBuksApp/ios'

test.describe.configure({ mode: 'serial' })

test.beforeAll(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A cover: a diagonal blend of the quiz's two colours, as a PNG. */
function cover([from, to]: StoreQuiz['colours'], width = 960, height = 540): Buffer {
  const raw = Buffer.alloc((width * 3 + 1) * height)
  for (let y = 0; y < height; y++) {
    const row = y * (width * 3 + 1)
    for (let x = 0; x < width; x++) {
      const t = (x + y) / (width + height)
      for (let c = 0; c < 3; c++) raw[row + 1 + x * 3 + c] = Math.round(from[c] + (to[c] - from[c]) * t)
    }
  }
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    return c >>> 0
  })
  const crc = (data: Buffer) => {
    let c = 0xffffffff
    for (const byte of data) c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8)
    return (c ^ 0xffffffff) >>> 0
  }
  const chunk = (kind: string, data: Buffer) => {
    const body = Buffer.concat([Buffer.from(kind, 'ascii'), data])
    const length = Buffer.alloc(4)
    length.writeUInt32BE(data.length)
    const sum = Buffer.alloc(4)
    sum.writeUInt32BE(crc(body))
    return Buffer.concat([length, body, sum])
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(width, 0)
  header.writeUInt32BE(height, 4)
  header.set([8, 2, 0, 0, 0], 8)
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))])
}

/** A live publication of a store quiz: three questions, a point each; two or more win the shop's prize. */
async function publish(request: APIRequestContext, token: string, quiz: StoreQuiz,
  language: Language): Promise<string> {
  const uploaded = await request.post(`/api/files?policy=${encodeURIComponent('qb.content.image')}`, {
    headers: { Authorization: `Bearer ${token}` },
    multipart: { file: { name: 'cover.png', mimeType: 'image/png', buffer: cover(quiz.colours) } },
  })
  expect(uploaded.status(), await uploaded.text()).toBeLessThan(300)
  // The quiz's language (Q59a): the home page lists only the taker's languages, and the request's is not the quiz's.
  const saved = await run(request, token, 'QB_QUIZ_SAVE', { title: quiz.title, intro: quiz.intro,
    cover: (await uploaded.json()).fileId, timeLimitSec: 300, language })
  const quizId = saved.quizId as string
  for (const question of quiz.questions) {
    await run(request, token, 'QB_QUESTION_SAVE', { quizId, stem: question.stem, points: 1,
      options: question.options.map((text, n) => ({ text, correct: n === 0 })) })
  }
  const version = await run(request, token, 'QB_QUIZ_PUBLISH_VERSION', { quizId })
  const now = Date.now()
  const publication = await run(request, token, 'QB_PUBLICATION_SAVE', {
    versionId: version.versionId, name: quiz.title, regions: ['GLOBAL'],
    startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 14 * 86_400_000).toISOString(),
    timeLimitSec: 300, passScore: 2, rewardMethod: 'OFFLINE_REDEEM', redeemDescription: quiz.prize,
    tiers: [{ fromScore: 0, toScore: 1, reward: 0 }, { fromScore: 2, toScore: 3, reward: quiz.value }],
  })
  const publicationId = publication.publicationId as string
  const submitted = await run(request, token, 'QB_PUBLICATION_SUBMIT', { publicationId })
  if (submitted.approval === 'PENDING') {
    await run(request, await adminToken(request), 'APPROVAL_DECIDE', { requestId: submitted.approvalRequestId,
      decision: 'APPROVE' })
  }
  return publicationId
}

/** Full marks through the API, as the app would submit them. */
async function answerAll(request: APIRequestContext, token: string, publicationId: string, quiz: StoreQuiz) {
  const started = await run(request, token, 'QB_ATTEMPT_START', { publicationId })
  const questions = started.questions as { no: number; stem: string; options: { no: number; text: string }[] }[]
  const answers = questions.map((q) => {
    const right = quiz.questions.find((mine) => mine.stem === q.stem)!.options[0]
    return { questionNo: q.no, selected: q.options.filter((o) => o.text === right).map((o) => o.no) }
  })
  await run(request, token, 'QB_ATTEMPT_SUBMIT', { attemptId: started.attemptId, publicationId, answers })
}

/** A store's phone: its size, its browser and the shell it plays (Q49b, Q49c). */
interface Device {
  name: 'android' | 'iphone'
  shell: 'android' | 'ios'
  /** Play's phone screenshots (9:16, 1080 × 1920); App Store's 6.9-inch iPhone (1320 × 2868). */
  viewport: { width: number; height: number }
  userAgent: string
  /** The iPhone's pictures come from WebKit, the engine of the iOS app's web view. */
  engine: 'chromium' | 'webkit'
}

const DEVICES: Device[] = [
  { name: 'android', shell: 'android', viewport: { width: 360, height: 640 }, userAgent: ANDROID_CHROME,
    engine: 'chromium' },
  { name: 'iphone', shell: 'ios', viewport: { width: 440, height: 956 }, userAgent: IPHONE_APP, engine: 'webkit' },
]

async function shoot(page: Page, dir: string, name: string) {
  await animationsSettled(page)
  // JPEG: neither store takes an alpha channel in screenshots.
  await page.screenshot({ path: `${dir}/${name}.jpg`, type: 'jpeg', quality: 92, animations: 'disabled' })
}

/**
 * One device's pictures: a new taker (one who has answered nothing) answers the second quiz through the API and the
 * first in the phone, as the store app opens it (`?shell=…`).
 */
async function photograph(chromium: Browser, request: APIRequestContext, language: Language, publications: string[],
  device: Device) {
  const content = CONTENT[language]
  const dir = `${OUT}/${language}/${device.name}`
  mkdirSync(dir, { recursive: true })
  const me = await takerInJapan(request, content.taker)
  for (const id of publications) await untilLive(request, me.token, id)
  await answerAll(request, me.token, publications[1], content.quizzes[1])

  const browser = device.engine === 'webkit' ? await webkit.launch() : chromium
  const context = await browser.newContext({ viewport: device.viewport, deviceScaleFactor: 3, isMobile: true,
    hasTouch: true, userAgent: device.userAgent, locale: { ja: 'ja-JP', zh: 'zh-CN', en: 'en-US' }[language],
    timezoneId: 'Asia/Tokyo', baseURL: test.info().project.use.baseURL })
  const page = await context.newPage()
  const home = `/taker/?shell=${device.shell}`

  // The introduction a newcomer sees first (Q32), in the phone's language.
  await page.goto(home)
  await expect(page.getByTestId('intro')).toBeVisible()
  await shoot(page, dir, '1-intro')

  // The sign-in page is English (signIn's placeholders); the app is then switched to the language and reopened
  // as the store app opens it.
  await page.evaluate(() => window.localStorage.setItem('jabiz.language', 'en'))
  await signIn(page, '/taker/', me.email)
  await expect(page.getByTestId('home')).toBeVisible()
  await page.evaluate((lang) => window.localStorage.setItem('jabiz.language', lang), language)
  await page.goto(home)
  await expect(page.getByTestId('feed-card').filter({ hasText: content.quizzes[0].title })).toBeVisible()
  await expect(page.getByTestId('feed-card').filter({ hasText: content.quizzes[2].title })).toBeVisible()
  await expect(page.getByTestId('pwa-install')).toHaveCount(0)
  await shoot(page, dir, '2-home')

  const quiz = content.quizzes[0]
  await page.getByTestId('feed-card').filter({ hasText: quiz.title }).getByTestId('feed-card-link').click()
  await expect(page.getByTestId('detail-start')).toBeVisible()
  await shoot(page, dir, '3-quiz')

  await page.getByTestId('detail-start').click()
  await expect(page).toHaveURL(/\/play\/[^/]+$/)
  for (const [n, question] of quiz.questions.entries()) {
    await expect(page.getByTestId('play-question')).toContainText(question.stem)
    const saved = page.waitForResponse((r) => r.url().includes('/api/processes/QB_ATTEMPT_SAVE/') && r.ok())
    await page.getByTestId('play-option').filter({ hasText: question.options[0] }).click()
    await saved
    if (n === 0) await shoot(page, dir, '4-answering')
    if (n < quiz.questions.length - 1) await page.getByTestId('play-next').click()
  }
  const submit = page.getByTestId('play-submit')
  const label = (await submit.innerText()).trim()
  await submit.click()
  await page.getByRole('alertdialog').getByRole('button', { name: label, exact: true }).click()
  await expect(page).toHaveURL(/\/result\/[^/]+$/)
  await expect(page.getByTestId('result-score')).toHaveText(`${quiz.questions.length} / ${quiz.questions.length}`)
  await expect(page.getByTestId('redeem-prize')).toHaveText(quiz.prize)
  await expect(page.getByTestId('redeem-code')).toBeVisible()
  await page.evaluate(() => window.scrollTo(0, 0))
  await shoot(page, dir, '5-result')

  await page.getByTestId('nav-history').click()
  await expect(page.getByTestId('history-row')).toHaveCount(2)
  await shoot(page, dir, '6-history')
  await context.close()
  if (browser !== chromium) await browser.close()
}

for (const language of ['ja', 'zh', 'en'] as const) {
  test(`the store's screenshots in ${language}`, async ({ browser, request }) => {
    test.setTimeout(420_000)
    const content = CONTENT[language]
    const sponsor = await approvedSponsor(request, content.contact, content.company)
    // The launch phase's one way to reward (qb.reward.default-methods = OFFLINE_REDEEM), allowed for this sponsor as
    // an administrator does on the sponsor review page (it asks for a verified address: a content administrator).
    const userId = (await rows(request, sponsor.token, 'urn:jabiz:dataset:sponsor:QbSponsor', []))[0].attributes.userId
    const allower = await tokenOf(request, await contentAdmin(request, 'Rin'), PASSWORD, 'admin')
    await run(request, allower, 'QB_SPONSOR_REWARD_METHODS_SET', { userId, methods: ['OFFLINE_REDEEM'],
      note: 'store screenshots' })
    const publications: string[] = []
    for (const quiz of content.quizzes) publications.push(await publish(request, sponsor.token, quiz, language))

    for (const device of DEVICES) await photograph(browser, request, language, publications, device)

    // Play's feature graphic (1024 × 500): the app's colours and the language's line.
    const graphic = await browser.newPage({ viewport: { width: 1024, height: 500 } })
    await graphic.setContent(FEATURE(language))
    await graphic.screenshot({ path: `${OUT}/${language}/feature-graphic.jpg`, type: 'jpeg', quality: 92 })
    await graphic.close()

    for (const publicationId of publications) await run(request, sponsor.token, 'QB_PUBLICATION_PAUSE', { publicationId })
  })
}
