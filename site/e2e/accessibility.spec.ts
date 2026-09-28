import { expect, headingProblems, LOCALES, pages, seedWorld, seriousViolations, settled, test, type World } from './support'

/**
 * Every page in the three languages, on a desktop and at 375 px (docs/culture/ROADMAP.md, C3; design section 9.6):
 * no critical or serious axe findings, one h1 and no skipped heading level, and no sideways scrolling on a phone
 * (the same layout a 750 px window shows at 200 % zoom).
 */
test.describe.configure({ mode: 'serial' })

let w: World

test.beforeAll(async ({ request, browser }) => {
  w = await seedWorld(request, browser)
})

const VIEWPORTS = [
  { name: 'desktop', width: 1280, height: 900 },
  { name: 'phone', width: 375, height: 740 },
] as const

for (const viewport of VIEWPORTS) {
  for (const locale of LOCALES) {
    test(`all pages in ${locale} on a ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      const failures: string[] = []
      for (const p of pages(w)) {
        await page.goto(`/${locale}/${p.path}`)
        await settled(page)
        for (const v of await seriousViolations(page)) failures.push(`${p.name}: ${v}`)
        for (const h of await headingProblems(page)) failures.push(`${p.name}: ${h}`)
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
        if (overflow > 0) failures.push(`${p.name}: scrolls sideways by ${overflow}px`)
      }
      expect(failures).toEqual([])
    })
  }
}
