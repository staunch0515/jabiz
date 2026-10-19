import AxeBuilder from '@axe-core/playwright'
import { expect, type Page } from '@playwright/test'

/**
 * `@jabiz/ui/testing/playwright`: the accessibility check of end-to-end tests (ROADMAP phase 14m; decision D34 item 3;
 * docs/design/12-frontend.md section 11), for the platform's own and for applications' SPAs alike. It needs
 * `@playwright/test` and `@axe-core/playwright` (optional peers of @jabiz/ui, at the platform's versions:
 * `pnpm app:check`). Automatic checks find about a third of the failures an audit finds: they do not replace one,
 * they keep what they can see from coming back.
 */

/** WCAG 2.2 level A and AA, as the component tests (`@jabiz/ui/testing`). */
export const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

/** The appearances of a jabiz frontend (decision D34 item 3). */
export type Appearance = 'light' | 'dark' | 'system'

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

/**
 * Checks the page as it is now with axe, colour contrast included, once its animations have settled; a violation is
 * a soft failure named after `name`, so that one run reports every page.
 */
export async function expectAccessible(page: Page, name: string) {
  await animationsSettled(page)
  const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
  const found = results.violations.map((v) => ({
    rule: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.slice(0, 5).map((n) => `${n.target.join(' ')}: ${n.failureSummary?.split('\n').slice(1).join(' ') ?? ''}`),
  }))
  expect.soft(found, `${name}: WCAG A/AA violations`).toEqual([])
}

/**
 * Opens every page of `page` from now on in `appearance`, as the person's choice (`AppearanceProvider` keeps it in
 * `localStorage` under `jabiz.appearance`): call it before the first navigation.
 */
export async function useAppearance(page: Page, appearance: Appearance) {
  await page.addInitScript((value) => window.localStorage.setItem('jabiz.appearance', value), appearance)
}
