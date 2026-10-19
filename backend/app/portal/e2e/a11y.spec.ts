import { expect, test } from '@playwright/test'
import { expectAccessible, setAppearance } from '@jabiz/ui/testing/playwright'

/*
 * How an application's SPA checks its pages in a browser with the platform's helper (ROADMAP 16v-1,
 * docs/guide/app-spa.md): its own Playwright and `expect`, handed to the helper. The demo portal's pages run in the
 * platform's end-to-end suite (frontend/e2e/a11y.spec.ts), against the packaged application; this file is
 * type-checked and linted with the portal (`pnpm typecheck`, `pnpm lint`) so that the helper's interface is proven
 * from an application's own dependencies; an application with its own Playwright configuration runs such a file.
 */
for (const appearance of ['light', 'dark'] as const) {
  test(`the unsubscribe page, ${appearance}`, async ({ page }) => {
    await setAppearance(page, appearance)
    await page.goto('/portal/mail/unsubscribe?token=not-a-token')
    await expect(page.getByTestId('unsubscribe-state')).toHaveAttribute('data-state', 'invalid')
    await expectAccessible(page, `portal unsubscribe (${appearance})`, { expect })
  })
}
