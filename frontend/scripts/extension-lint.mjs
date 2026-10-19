const PLATFORM_ONLY_THROUGH_ADMIN = {
  group: ['**/frontend/**', 'virtual:jabiz-extension'],
  message: 'An extension uses the platform only through @jabiz/admin (decision D22).',
}

/**
 * The frontend packages, which @jabiz/admin re-exports (decisions D22, D34): an extension imports them through it,
 * so that what it uses is the contract of @jabiz/admin and nothing else.
 */
const PACKAGES_THROUGH_ADMIN = {
  // A regular expression: a gitignore pattern cannot leave out @jabiz/ui/testing once @jabiz/ui is matched.
  regex: '^@jabiz/(client(/.*)?|ui(/(?!testing(/|$)).*)?)$',
  message: 'An extension uses @jabiz/ui and @jabiz/client only through @jabiz/admin (decision D22).',
}

/** The accessibility check of @jabiz/ui, which an extension's tests take from @jabiz/admin/testing. */
const UI_TESTING_THROUGH_ADMIN = {
  group: ['@jabiz/ui/testing', '@jabiz/ui/testing/*'],
  message: "An extension's tests take the accessibility check from @jabiz/admin/testing (decision D22).",
}

/** @jabiz/admin/testing brings in Vitest and vitest-axe: never in a page, only in a test. */
const TESTING_ONLY_IN_TESTS = {
  group: ['@jabiz/admin/testing'],
  message: '@jabiz/admin/testing is for tests only (*.test.ts, *.test.tsx): it brings in Vitest (decision D22).',
}

/**
 * Ant Design left the platform (decision D34, phase 15d). The admin frontend refuses it with this same pattern
 * (eslint.config.js); the message names the replacement, clearer than a module that cannot be found.
 */
export const NO_ANT_DESIGN = {
  group: ['antd', 'antd/*', '@ant-design/*'],
  message: 'Ant Design left the platform (decision D34): use @jabiz/ui (in an extension, through @jabiz/admin).',
}

/** An extension's tests: where @jabiz/admin/testing may be imported. */
export const TEST_FILES = ['**/*.test.{ts,tsx}', '**/*.spec.{ts,tsx}']

const COMMON = [PLATFORM_ONLY_THROUGH_ADMIN, NO_ANT_DESIGN, PACKAGES_THROUGH_ADMIN, UI_TESTING_THROUGH_ADMIN]

/**
 * Lint rules an admin extension gets on top of the platform's (decisions D22, D34): the platform only via
 * @jabiz/admin (its tests also via @jabiz/admin/testing, which pages may not import), and no Ant Design. Two configs:
 * a later `no-restricted-imports` replaces an earlier one, so each set of files gets its own complete list.
 */
export const extensionRules = [
  {
    files: ['**/*.{ts,tsx}'],
    ignores: TEST_FILES,
    rules: { 'no-restricted-imports': ['error', { patterns: [...COMMON, TESTING_ONLY_IN_TESTS] }] },
  },
  {
    files: TEST_FILES,
    rules: { 'no-restricted-imports': ['error', { patterns: COMMON }] },
  },
]

/**
 * The rules for an extension (called with its directory): the same for every extension (the demo extension's
 * exemption from the Ant Design rule ended when it moved to @jabiz/ui, phase 15c-2).
 */
export function extensionRulesFor() {
  return extensionRules
}
