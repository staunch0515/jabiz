import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'
import { NO_ANT_DESIGN } from './scripts/extension-lint.mjs'

/** A class name of Ant Design in a selector of the end-to-end tests: they find elements by role, name or data-*. */
const ANT_CLASS = 'Ant Design left the platform (decision D34): find elements by role, name or a data-* attribute (e2e/support.ts), not by .ant-* classes.'

export default defineConfig([
  globalIgnores(['dist', 'packages/client/src/api/schema.d.ts', 'playwright-report', 'test-results']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // The same pattern as an extension's (scripts/extension-lint.mjs), so Ant Design does not come back (phase 15d).
      'no-restricted-imports': ['error', { patterns: [NO_ANT_DESIGN] }],
    },
  },
  {
    files: ['e2e/**/*.ts'],
    rules: {
      'no-restricted-syntax': [
        'error',
        { selector: 'Literal[value=/\\.ant-/]', message: ANT_CLASS },
        { selector: 'TemplateElement[value.raw=/\\.ant-/]', message: ANT_CLASS },
      ],
    },
  },
  {
    // Libraries, not an application: their modules export helpers next to components, and fast refresh of the
    // admin's pages does not depend on them.
    files: ['packages/*/src/**/*.{ts,tsx}'],
    rules: { 'react-refresh/only-export-components': 'off' },
  },
])
