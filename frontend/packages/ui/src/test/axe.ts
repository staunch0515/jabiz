import { expect } from 'vitest'
import { axe } from 'vitest-axe'

/** WCAG 2.2 level A and AA, as the end-to-end checks (e2e/a11y.spec.ts). */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

/**
 * Checks what is rendered with axe, once in the light and once in the dark appearance (decision D34 item 3), and
 * fails on any violation. jsdom computes no styles, so colour contrast is not checked here: theme.test.ts checks the
 * tokens' contrast, and the end-to-end checks the pages in a browser. Portals (dialogs, menus) render into
 * document.body, which is what is checked.
 */
export async function expectAccessible(root: Element = document.body) {
  for (const appearance of ['light', 'dark'] as const) {
    document.documentElement.classList.toggle('dark', appearance === 'dark')
    const results = await axe(root, {
      runOnly: { type: 'tag', values: TAGS },
      rules: { 'color-contrast': { enabled: false } },
    })
    const found = results.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(' ')).join(', ')})`)
    expect(found, `axe violations in the ${appearance} appearance`).toEqual([])
  }
  document.documentElement.classList.remove('dark')
}
