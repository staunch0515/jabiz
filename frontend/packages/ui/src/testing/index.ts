import { expect } from 'vitest'
import { axe } from 'vitest-axe'
import { WCAG_TAGS } from './tags'

/**
 * `@jabiz/ui/testing`: the accessibility check of component and page tests (decision D34 item 3;
 * docs/design/12-frontend.md section 12), for the platform's own tests and for applications' SPAs alike. It needs
 * `vitest` and `vitest-axe` (optional peers of @jabiz/ui, at the platform's versions: `pnpm app:check`).
 */

export { WCAG_TAGS }

/**
 * Checks what is rendered with axe, once in the light and once in the dark appearance (decision D34 item 3), and
 * fails on any violation. jsdom computes no styles, so colour contrast is not checked here: the platform's theme test
 * checks the tokens' contrast, and the end-to-end checks the pages in a browser. Portals (dialogs, menus) render into
 * document.body, which is what is checked unless `root` is given. The content of frames is not entered (axe cannot
 * reach a jsdom frame, and what a frame shows, such as a mail's preview, is not the page's); the frame element itself
 * (its title) is checked.
 */
export async function expectAccessible(root: Element = document.body) {
  try {
    for (const appearance of ['light', 'dark'] as const) {
      document.documentElement.classList.toggle('dark', appearance === 'dark')
      const results = await axe(root, {
        runOnly: { type: 'tag', values: [...WCAG_TAGS] },
        iframes: false,
        rules: { 'color-contrast': { enabled: false } },
      })
      const found = results.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(' ')).join(', ')})`)
      expect(found, `axe violations in the ${appearance} appearance`).toEqual([])
    }
  } finally {
    document.documentElement.classList.remove('dark')
  }
}
