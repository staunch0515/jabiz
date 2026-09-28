import AxeBuilder from '@axe-core/playwright'
import { test as base, expect, type APIRequestContext, type Browser, type Page } from '@playwright/test'

/**
 * Playwright's test for the public site, failing any test during which the page reported a Content-Security-Policy
 * violation: the server sends the site's own policy (docs/culture/00-design.md section 9.4).
 */
export const test = base.extend<{ contentSecurityPolicy: void }>({
  contentSecurityPolicy: [
    async ({ page }, use) => {
      const violations: string[] = []
      page.on('console', (message) => {
        if (message.type() === 'error' && message.text().includes('Content Security Policy')) violations.push(message.text())
      })
      await use()
      expect(violations, 'Content-Security-Policy violations').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }

export const LOCALES = ['en', 'zh', 'ja'] as const

/** The administrator created at server start (JABIZ_BOOTSTRAP_ADMIN_*); content is prepared through the admin API. */
const ADMIN = {
  userName: process.env.E2E_ADMIN_USER ?? 'admin',
  password: process.env.E2E_ADMIN_PASSWORD ?? 'admin-password-1',
}

/** A slug no earlier run used: the suite only adds content, so it can run against the same database again. */
export function unique(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}${Math.floor(Math.random() * 36 ** 3).toString(36)}`
}

const dataset = (entity: string) => `urn:jabiz:dataset:culture:${entity}`

class Admin {
  private readonly request: APIRequestContext
  private readonly token: string

  constructor(request: APIRequestContext, token: string) {
    this.request = request
    this.token = token
  }

  private get headers() {
    return { Authorization: `Bearer ${this.token}` }
  }

  async create(entity: string, attributes: Record<string, unknown>): Promise<string> {
    const response = await this.request.post(`/api/datasets/${encodeURIComponent(dataset(entity))}/commit`, {
      headers: this.headers,
      data: { changes: [{ action: 'INSERT', attributes }] },
    })
    expect(response.status(), await response.text()).toBe(200)
    return (await response.json())[0].id as string
  }

  async run(process: string, input: Record<string, unknown>) {
    const response = await this.request.post(`/api/processes/${process}/latest`, { headers: this.headers, data: input })
    expect(response.status(), `${process}: ${await response.text()}`).toBe(200)
  }

  async upload(policy: string, name: string, mimeType: string, buffer: Buffer): Promise<string> {
    const response = await this.request.post(`/api/files?policy=${policy}`, {
      headers: this.headers,
      multipart: { file: { name, mimeType, buffer } },
    })
    expect(response.status(), await response.text()).toBe(201)
    return (await response.json()).fileId as string
  }
}

export async function signIn(request: APIRequestContext): Promise<Admin> {
  const response = await request.post('/api/auth/login', { data: ADMIN })
  expect(response.status(), await response.text()).toBe(200)
  const admin = new Admin(request, (await response.json()).accessToken)
  // Idempotent: roles, menus and the two switches (docs/culture/00-design.md section 6.6).
  await admin.run('CULTURE_SETUP', {})
  return admin
}

/** A photograph-sized JPEG drawn in the browser: wide enough that the image variants exist. */
async function jpeg(browser: Browser, width: number, height: number, hue: number): Promise<Buffer> {
  const page = await browser.newPage()
  const dataUrl = await page.evaluate(
    ([w, h, hue]) => {
      const canvas = document.createElement('canvas')
      canvas.width = w
      canvas.height = h
      const g = canvas.getContext('2d')!
      g.fillStyle = `hsl(${hue} 25% 70%)`
      g.fillRect(0, 0, w, h)
      g.fillStyle = `hsl(${hue} 25% 40%)`
      g.beginPath()
      g.arc(w / 2, h * 0.4, Math.min(w, h) * 0.2, 0, Math.PI * 2)
      g.fill()
      g.fillRect(w * 0.2, h * 0.65, w * 0.6, h * 0.35)
      return canvas.toDataURL('image/jpeg', 0.8)
    },
    [width, height, hue] as const,
  )
  await page.close()
  return Buffer.from(dataUrl.split(',')[1], 'base64')
}

const en = (text: string) => ({ en: text })

/** Everything the new content is reachable by. */
export interface World {
  place: string
  placeName: string
  theme: string
  themeTitle: string
  themeQuestion: string
  person: string
  personName: string
  story: string
  storyTitle: string
  perspectiveHeading: string
  resource: string
  resourceTitle: string
}

/**
 * A new place, theme, participant (adult, with consent, active), a published video story with the participant's
 * perspective, and a published teaching resource: made through the admin API exactly as editors make them.
 * Names are unique so that the tests can tell this run's content from earlier runs'.
 */
export async function seedWorld(request: APIRequestContext, browser: Browser): Promise<World> {
  const admin = await signIn(request)
  const tag = unique('e2e')
  const w: World = {
    place: `${tag}-place`,
    placeName: `Harbour town ${tag}`,
    theme: `${tag}-theme`,
    themeTitle: `Weekends ${tag}`,
    themeQuestion: `What do weekends look like where you live (${tag})?`,
    person: `${tag}-person`,
    personName: `Noa ${tag.slice(-4)}`,
    story: `${tag}-story`,
    storyTitle: `Saturday mornings ${tag}`,
    perspectiveHeading: `The market opens at six ${tag}`,
    resource: `${tag}-resource`,
    resourceTitle: `What is a weekend? ${tag}`,
  }
  const location = await admin.create('Location', {
    slug: w.place,
    name: { en: w.placeName, ja: `港町 ${tag}` },
    countryCode: 'NZ',
    placeLabel: en('Coastal town'),
    latitude: -41.28,
    longitude: 174.77,
    sortOrder: 900,
    visible: true,
  })
  const theme = await admin.create('Theme', {
    slug: w.theme,
    icon: '🗓️',
    title: en(w.themeTitle),
    question: en(w.themeQuestion),
    intro: en('Weekends are **ordinary**, which is why we look at them.'),
    sortOrder: 900,
    visible: true,
  })
  const portrait = await admin.upload('culture.image', 'portrait.jpg', 'image/jpeg', await jpeg(browser, 800, 1000, 30))
  const participant = await admin.create('Participant', {
    slug: w.person,
    displayName: w.personName,
    locationId: location,
    adult: true,
    portraitFileId: portrait,
    portraitAlt: en(`${w.personName} on a harbour wall`),
    shortBio: en('Interested in markets, boats and early mornings.'),
    bio: en('I grew up by the sea. [Our method](/en/method) explains how I work.'),
    perspective: en('What I notice is shaped by growing up in a small town.'),
    reflection: en('I learned that my weekends are not "normal" everywhere.'),
    interests: en('markets, boats, mornings'),
    languages: 'English, Te reo Māori',
    sortOrder: 900,
  })
  await admin.create('Consent', {
    participantId: participant,
    party: 'PARTICIPANT',
    coversPhoto: true,
    coversVideo: true,
    coversVoice: true,
    signedOn: '2026-01-10T00:00:00Z',
  })
  await admin.run('CULTURE_PARTICIPANT_ACTIVATE', { participantId: participant })

  const thumbnail = await admin.upload('culture.image', 'thumbnail.jpg', 'image/jpeg', await jpeg(browser, 1600, 900, 200))
  const story = await admin.create('Story', {
    slug: w.story,
    title: en(w.storyTitle),
    summary: en('What one Saturday morning looks like, from the harbour.'),
    about: en('We asked: is a weekend a time, a place, or a habit?'),
    mediaType: 'VIDEO',
    storyDate: '2026-04-18T00:00:00Z',
    thumbnailFileId: thumbnail,
    thumbnailAlt: en('Fishing boats at a harbour at dawn'),
    videoProvider: 'YOUTUBE',
    videoId: 'aqz-KE-bpKQ',
    captionsConfirmed: true,
    transcript: en('The market opens at six. Everyone knows everyone.'),
    reflectionSurprised: en('How early everything starts.'),
    reflectionAssumed: en('That weekends are for sleeping in.'),
    reflectionLearned: en('A weekend is a habit more than a time.'),
    featured: true,
  })
  await admin.create('StoryTheme', { storyId: story, themeId: theme })
  await admin.create('Contribution', {
    storyId: story,
    participantId: participant,
    heading: en(w.perspectiveHeading),
    text: en('My uncle sells fish. I help him from six until nine, then the town wakes up.'),
    videoProvider: 'VIMEO',
    videoId: '76979871',
    captionsConfirmed: true,
    transcript: en('Six o’clock, the boats come in.'),
  })
  await admin.run('CULTURE_STORY_PUBLISH', { storyId: story })

  const resource = await admin.create('Resource', {
    slug: w.resource,
    title: en(w.resourceTitle),
    description: en('Compare two weekends and ask what "normal" means.'),
    activityType: 'DISCUSSION',
    ageGroup: '14-16',
    durationMinutes: 45,
    body: en('1. Watch the story.\n2. Describe your own Saturday.\n3. Compare.'),
    sortOrder: 900,
  })
  await admin.create('ResourceStory', { resourceId: resource, storyId: story })
  await admin.run('CULTURE_RESOURCE_PUBLISH', { resourceId: resource })
  return w
}

/** Every page of the site for the seeded content (design section 9.1; map and search are stage C4). */
export function pages(w: World): { name: string; path: string }[] {
  return [
    { name: 'home', path: '' },
    { name: 'people', path: 'people' },
    { name: 'person', path: `people/${w.person}` },
    { name: 'themes', path: 'themes' },
    { name: 'theme', path: `themes/${w.theme}` },
    { name: 'stories', path: 'stories' },
    { name: 'story', path: `stories/${w.story}` },
    { name: 'method', path: 'method' },
    { name: 'resources', path: 'resources' },
    { name: 'resource', path: `resources/${w.resource}` },
    { name: 'about', path: 'about' },
    { name: 'not found', path: 'stories/no-such-story' },
  ]
}

/** Waits until the page has its content: an h1, and no part still loading. */
export async function settled(page: Page) {
  await expect(page.locator('h1')).toHaveCount(1)
  await expect(page.getByRole('status').filter({ hasText: /Loading|加载中|読み込み中/ })).toHaveCount(0)
  await page.waitForLoadState('networkidle')
}

/** axe's critical and serious findings (design section 9.6). */
export async function seriousViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze()
  return results.violations
    .filter((v) => v.impact === 'critical' || v.impact === 'serious')
    .map((v) => `${v.id}: ${v.help} — ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)
}

/** Heading levels in document order: one h1, and never a skipped level (design section 9.6). */
export async function headingProblems(page: Page): Promise<string[]> {
  const levels = await page.locator('h1, h2, h3, h4, h5, h6').evaluateAll((els) =>
    els.filter((el) => (el as HTMLElement).offsetParent !== null || el.getClientRects().length > 0).map((el) => Number(el.tagName[1])),
  )
  const problems: string[] = []
  if (levels.filter((l) => l === 1).length !== 1) problems.push(`h1 count: ${levels.filter((l) => l === 1).length}`)
  levels.forEach((level, i) => {
    if (i > 0 && level > levels[i - 1] + 1) problems.push(`h${levels[i - 1]} followed by h${level}`)
  })
  return problems
}

/** Presses Tab until `target` has the focus, as a keyboard user would; fails after `max` presses. */
export async function tabTo(page: Page, target: ReturnType<Page['locator']>, max = 150) {
  const handle = await target.elementHandle()
  for (let i = 0; i < max; i++) {
    await page.keyboard.press('Tab')
    if (await page.evaluate((el) => document.activeElement === el, handle)) return
  }
  throw new Error(`Could not reach ${target} with the keyboard in ${max} presses`)
}
