import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist', 'node_modules']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, tseslint.configs.recommended, reactHooks.configs.flat.recommended],
    languageOptions: { globals: globals.browser },
  },
  {
    // Q113 §3.1: the timeline's numbers live in frame/xspec.ts only; the frame's classes read its variables
    // (`h-(--x-header)`), so a pixel value of the spec written out in the frame is refused.
    files: ['src/frame/**/*.{ts,tsx}'],
    ignores: ['src/frame/xspec.ts', 'src/frame/**/*.test.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': ['error',
        { selector: String.raw`Literal[value=/\b(8|12|13|16|20|26|32|36|40|53|56|106|233|275|350|600)px\b/]`,
          message: 'A number of the timeline spec: name it in frame/xspec.ts and use its variable (Q113 §3.1).' },
        { selector: String.raw`TemplateElement[value.raw=/\b(8|12|13|16|20|26|32|36|40|53|56|106|233|275|350|600)px\b/]`,
          message: 'A number of the timeline spec: name it in frame/xspec.ts and use its variable (Q113 §3.1).' },
      ],
    },
  },
])
