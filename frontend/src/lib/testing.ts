/**
 * `@jabiz/admin/testing`: the accessibility check for an admin extension's tests (decisions D22 and D34 item 3;
 * docs/design/12-frontend.md sections 9 and 11). It is part of the extension contract, like `@jabiz/admin`: an
 * extension reaches the platform through these two only, and imports this one in its tests only (the extension lint
 * refuses it elsewhere), since it brings in Vitest and vitest-axe, which no production bundle may contain.
 * `@jabiz/admin` itself never imports it (src/lib/testing.test.ts).
 */
export { expectAccessible, WCAG_TAGS } from '@jabiz/ui/testing'
