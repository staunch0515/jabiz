import tseslint from 'typescript-eslint'

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

/** @jabiz/admin/testing brings in Vitest and vitest-axe: never in production code, only in tests. */
const TESTING_ONLY_IN_TESTS = {
  group: ['@jabiz/admin/testing'],
  message: '@jabiz/admin/testing is for tests only (*.test.*, *.spec.*, test/**): it brings in Vitest (decision D22).',
}

/**
 * Ant Design left the platform (decision D34, phase 15d). The admin frontend refuses it with this same pattern
 * (eslint.config.js); the message names the replacement, clearer than a module that cannot be found.
 */
export const NO_ANT_DESIGN = {
  group: ['antd', 'antd/*', '@ant-design/*'],
  message: 'Ant Design left the platform (decision D34): use @jabiz/ui (in an extension, through @jabiz/admin).',
}

const EXTENSIONS = '{js,jsx,mjs,cjs,ts,tsx,mts,cts}'

/** Every source file of an extension: TypeScript and JavaScript alike, so that a .js file is no way around the rules. */
export const SOURCE_FILES = [`**/*.${EXTENSIONS}`]

/**
 * An extension's tests, where @jabiz/admin/testing may be imported: `*.test.*`, `*.spec.*`, and shared test helpers,
 * which live under a `test/` directory (`src/test/render.tsx`). Anything else (pages, stories) is production code.
 */
export const TEST_FILES = [`**/*.test.${EXTENSIONS}`, `**/*.spec.${EXTENSIONS}`, `**/test/**/*.${EXTENSIONS}`]

const COMMON = [PLATFORM_ONLY_THROUGH_ADMIN, NO_ANT_DESIGN, PACKAGES_THROUGH_ADMIN, UI_TESTING_THROUGH_ADMIN]

/**
 * What `no-restricted-imports` does not see: `import('…')` and `require('…')`. esquery's regular expressions cannot
 * hold a slash, hence the `.` in `@jabiz.ui`.
 */
function dynamic(pattern, message) {
  return [
    { selector: `ImportExpression > Literal.source[value=${pattern}]`, message },
    { selector: `ImportExpression > TemplateLiteral.source > TemplateElement[value.raw=${pattern}]`, message },
    { selector: `CallExpression[callee.name='require'] > Literal.arguments[value=${pattern}]`, message },
  ]
}

const DYNAMIC_PACKAGES = dynamic('/^@jabiz.(ui|client)([^a-z-]|$)/',
  'An extension uses @jabiz/ui and @jabiz/client only through @jabiz/admin, and its tests take the accessibility '
    + 'check from @jabiz/admin/testing (decision D22).')
const DYNAMIC_TESTING = dynamic('/^@jabiz.admin.testing$/', TESTING_ONLY_IN_TESTS.message)

/** JavaScript files are parsed as the TypeScript ones are (JSX included), so that the same rules run on them. */
const JAVASCRIPT = {
  files: ['**/*.{js,jsx,mjs,cjs}'],
  languageOptions: { parser: tseslint.parser, parserOptions: { ecmaFeatures: { jsx: true } } },
}

/**
 * Lint rules an admin extension gets on top of the platform's (decisions D22, D34): the platform only via
 * @jabiz/admin (its tests also via @jabiz/admin/testing, which production code may not import, statically or with
 * `import()`), and no Ant Design. Two configs: a later `no-restricted-imports` replaces an earlier one, so each set of
 * files gets its own complete list.
 */
export const extensionRules = [
  JAVASCRIPT,
  {
    files: SOURCE_FILES,
    ignores: TEST_FILES,
    rules: {
      'no-restricted-imports': ['error', { patterns: [...COMMON, TESTING_ONLY_IN_TESTS] }],
      'no-restricted-syntax': ['error', ...DYNAMIC_PACKAGES, ...DYNAMIC_TESTING],
    },
  },
  {
    files: TEST_FILES,
    rules: {
      'no-restricted-imports': ['error', { patterns: COMMON }],
      'no-restricted-syntax': ['error', ...DYNAMIC_PACKAGES],
    },
  },
]

/**
 * The rules for an extension (called with its directory): the same for every extension (the demo extension's
 * exemption from the Ant Design rule ended when it moved to @jabiz/ui, phase 15c-2).
 */
export function extensionRulesFor() {
  return extensionRules
}
