import AxeBuilder from '@axe-core/playwright'
import type { Page } from '@playwright/test'
import { WCAG_TAGS } from './tags'

/**
 * `@jabiz/ui/testing/playwright`: the accessibility check of end-to-end tests (ROADMAP phase 14m; decision D34 item 3;
 * docs/design/12-frontend.md section 11), for the platform's own and for applications' SPAs alike. It never loads
 * `@playwright/test` itself (its types only): the caller hands it its own `expect`, so that an SPA's run has one
 * Playwright. It needs `@axe-core/playwright` (an optional peer of @jabiz/ui, at the platform's version:
 * `pnpm app:check`). Automatic checks find about a third of the failures an audit finds: they do not replace one,
 * they keep what they can see from coming back.
 */

export { WCAG_TAGS }

/** The appearances of a jabiz frontend (decision D34 item 3). */
export type Appearance = 'light' | 'dark' | 'system'

/** One rule axe found broken, with up to five of the elements that break it. */
export interface AxeViolation {
  rule: string
  impact: string | null | undefined
  help: string
  nodes: string[]
}

/** The part of Playwright's `expect` the check uses: a soft assertion, so that one run reports every page. */
export interface SoftExpect {
  soft(actual: unknown, message?: string): { toEqual(expected: unknown): unknown }
}

/**
 * Waits until every animation that ends (menus and dialogs fading and sliding in) has ended: checked mid fade-in, a
 * half-transparent menu has blended colours whose contrast is not the one the user reads. Endless animations (a
 * spinner) are left alone.
 */
export async function animationsSettled(page: Page) {
  await page.waitForFunction(() =>
    document
      .getAnimations()
      .filter((a) => a.effect?.getComputedTiming().iterations !== Infinity)
      .every((a) => a.playState === 'finished' || a.playState === 'idle'),
  )
}

/** The WCAG A/AA violations on the page as it is now, colour contrast included, once its animations have settled. */
export async function accessibilityViolations(page: Page): Promise<AxeViolation[]> {
  await animationsSettled(page)
  const results = await new AxeBuilder({ page }).withTags([...WCAG_TAGS]).analyze()
  return results.violations.map((v) => ({
    rule: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.slice(0, 5).map((n) => `${n.target.join(' ')}: ${n.failureSummary?.split('\n').slice(1).join(' ') ?? ''}`),
  }))
}

/**
 * Checks the page as it is now ({@link accessibilityViolations}); a violation is a soft failure named after `name`,
 * asserted with the caller's `expect` (`await expectAccessible(page, 'sign-in', { expect })`).
 */
export async function expectAccessible(page: Page, name: string, { expect }: { expect: SoftExpect }) {
  expect.soft(await accessibilityViolations(page), `${name}: WCAG A/AA violations`).toEqual([])
}

/**
 * Opens every page of `page` from now on in `appearance`, as the person's choice (`AppearanceProvider` keeps it in
 * `localStorage` under `jabiz.appearance`): call it before the first navigation.
 */
export async function setAppearance(page: Page, appearance: Appearance) {
  await page.addInitScript((value) => window.localStorage.setItem('jabiz.appearance', value), appearance)
}
