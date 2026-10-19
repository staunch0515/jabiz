// @vitest-environment node
import { resolve } from 'node:path'
import { ESLint, type Linter } from 'eslint'
import { describe, expect, it } from 'vitest'
import platformConfig from '../eslint.config.js'
import { extensionRules, extensionRulesFor } from './extension-lint.mjs'

async function restricted(code: string, rules: Linter.Config[] = extensionRules, filePath = 'src/page.ts') {
  const eslint = new ESLint({ overrideConfigFile: true, overrideConfig: [...platformConfig, ...rules] })
  const [result] = await eslint.lintText(code, { filePath })
  expect(result.messages.filter((m) => m.fatal), `parse errors in ${filePath}`).toEqual([])
  return result.messages.filter((m) => m.ruleId === 'no-restricted-imports' || m.ruleId === 'no-restricted-syntax')
}

async function lint(code: string, rules: Linter.Config[] = extensionRules, filePath = 'src/page.ts') {
  return (await restricted(code, rules, filePath)).length
}

describe('extension lint rules (decisions D22, D34)', () => {
  it('allows the platform through @jabiz/admin and third-party packages', async () => {
    expect(await lint("import { api, Button } from '@jabiz/admin'\nimport { Boxes } from 'lucide-react'\nexport const x = [api, Button, Boxes]\n")).toBe(0)
  })

  it('refuses imports of the platform frontend and of the extension loader', async () => {
    expect(await lint("import { api } from '../../../frontend/src/api/client'\nexport const x = api\n")).toBe(1)
    expect(await lint("import e from 'virtual:jabiz-extension'\nexport const x = e\n")).toBe(1)
  })

  it('refuses Ant Design', async () => {
    expect(await lint("import { Button } from 'antd'\nexport const x = Button\n")).toBe(1)
    expect(await lint("import en from 'antd/locale/en_US'\nexport const x = en\n")).toBe(1)
    expect(await lint("import { UserOutlined } from '@ant-design/icons'\nexport const x = UserOutlined\n")).toBe(1)
  })

  it('refuses Ant Design in the demo extension too: its exemption ended with phase 15c-2', async () => {
    const demo = extensionRulesFor(resolve(import.meta.dirname, '../../backend/app/admin-extension'))
    expect(await lint("import { Button } from 'antd'\nexport const x = Button\n", demo)).toBe(1)
    expect(await lint("import e from 'virtual:jabiz-extension'\nexport const x = e\n", demo)).toBe(1)
    const other = extensionRulesFor(resolve(import.meta.dirname, '../../backend/quizbuks/admin-extension'))
    expect(await lint("import { Button } from 'antd'\nexport const x = Button\n", other)).toBe(1)
  })

  it('refuses @jabiz/ui and @jabiz/client, pointing to @jabiz/admin (phase 16w-1)', async () => {
    for (const source of ['@jabiz/ui', '@jabiz/ui/styles', '@jabiz/client', '@jabiz/client/vite', '@jabiz/client/testing']) {
      for (const filePath of ['src/page.tsx', 'src/page.test.tsx']) {
        const messages = await restricted(`import { x } from '${source}'\nexport const y = x\n`, extensionRules, filePath)
        expect(messages, `${source} in ${filePath}`).toHaveLength(1)
        expect(messages[0].message).toContain('only through @jabiz/admin')
      }
    }
  })

  it('refuses @jabiz/ui/testing, pointing to @jabiz/admin/testing', async () => {
    for (const source of ['@jabiz/ui/testing', '@jabiz/ui/testing/playwright']) {
      const messages = await restricted(`import { x } from '${source}'\nexport const y = x\n`, extensionRules,
        'src/page.test.tsx')
      expect(messages, source).toHaveLength(1)
      expect(messages[0].message).toContain('@jabiz/admin/testing')
    }
  })

  it('allows @jabiz/admin/testing in tests only', async () => {
    const code = "import { expectAccessible } from '@jabiz/admin/testing'\nexport const x = expectAccessible\n"
    expect(await lint(code, extensionRules, 'src/page.test.tsx')).toBe(0)
    expect(await lint(code, extensionRules, 'src/nested/a.test.ts')).toBe(0)
    expect(await lint(code, extensionRules, 'src/flow.spec.ts')).toBe(0)
    const inPage = await restricted(code, extensionRules, 'src/page.tsx')
    expect(inPage).toHaveLength(1)
    expect(inPage[0].message).toContain('for tests only')
    expect(await lint(code, extensionRules, 'src/testing.ts')).toBe(1)
  })

  it('refuses the packages and the test exports through import(), require() and re-exports (16w-1 review)', async () => {
    const page = 'src/page.tsx'
    for (const code of [
      "export const load = () => import('@jabiz/ui')\n",
      'export const load = () => import(`@jabiz/client/vite`)\n',
      "export const load = () => import('@jabiz/admin/testing')\n",
      "export * from '@jabiz/ui'\n",
      "export { Button } from '@jabiz/ui'\n",
      "export * from '@jabiz/admin/testing'\n",
      "import type { ButtonProps } from '@jabiz/ui'\nexport type P = ButtonProps\n",
      "import '@jabiz/admin/testing'\n",
    ]) {
      expect(await lint(code, extensionRules, page), code).toBe(1)
    }
    expect(await lint("export const load = () => import('@jabiz/admin')\n", extensionRules, page)).toBe(0)
    expect(await lint("export const load = () => import('./other')\n", extensionRules, page)).toBe(0)
    // In tests, import() of the packages is refused as well; of @jabiz/admin/testing it is allowed.
    expect(await lint("export const load = () => import('@jabiz/ui/testing')\n", extensionRules, 'src/a.test.ts'))
      .toBe(1)
    expect(await lint("export const load = () => import('@jabiz/admin/testing')\n", extensionRules,
      'src/a.test.ts')).toBe(0)
  })

  it('applies to JavaScript files and stories as to the pages', async () => {
    const testing = "import { expectAccessible } from '@jabiz/admin/testing'\nexport const x = expectAccessible\n"
    expect(await lint(testing, extensionRules, 'src/page.js')).toBe(1)
    expect(await lint(`${testing}export const el = <div />\n`, extensionRules, 'src/page.jsx')).toBe(1)
    expect(await lint("const ui = require('@jabiz/ui')\nmodule.exports = ui\n", extensionRules, 'src/old.cjs')).toBe(1)
    expect(await lint("import { Button } from '@jabiz/ui'\nexport const x = Button\n", extensionRules, 'src/a.mjs'))
      .toBe(1)
    // A story is production code, not a test.
    expect(await lint(testing, extensionRules, 'src/Page.stories.tsx')).toBe(1)
    // JavaScript tests are tests.
    expect(await lint(testing, extensionRules, 'src/page.test.jsx')).toBe(0)
  })

  it('lets shared test helpers under test/ import @jabiz/admin/testing', async () => {
    const testing = "import { expectAccessible } from '@jabiz/admin/testing'\nexport const x = expectAccessible\n"
    expect(await lint(testing, extensionRules, 'src/test/render.tsx')).toBe(0)
    expect(await lint(testing, extensionRules, 'src/pages/test/helpers.ts')).toBe(0)
    expect(await lint(testing, extensionRules, 'src/testing.ts')).toBe(1)
    expect(await lint(testing, extensionRules, 'src/render.test-utils.tsx')).toBe(1)
  })

  it('keeps the other rules in tests', async () => {
    expect(await lint("import { Button } from 'antd'\nexport const x = Button\n", extensionRules, 'src/a.test.tsx'))
      .toBe(1)
    expect(await lint("import e from 'virtual:jabiz-extension'\nexport const x = e\n", extensionRules,
      'src/a.test.tsx')).toBe(1)
    expect(await lint("import { api } from '@jabiz/admin'\nimport { it } from 'vitest'\nexport const x = [api, it]\n",
      extensionRules, 'src/a.test.tsx')).toBe(0)
  })
})

describe('platform lint rules against Ant Design coming back (phase 15d)', () => {
  async function messages(code: string, filePath: string) {
    const eslint = new ESLint({ overrideConfigFile: true, overrideConfig: platformConfig })
    const [result] = await eslint.lintText(code, { filePath })
    return result.messages.map((m) => m.ruleId)
  }

  it('refuses Ant Design in the admin frontend and its packages', async () => {
    expect(await messages("import { Button } from 'antd'\nexport const x = Button\n", 'src/page.ts'))
      .toEqual(['no-restricted-imports'])
    expect(await messages("import { ProTable } from '@ant-design/pro-components'\nexport const x = ProTable\n", 'packages/ui/src/x.ts'))
      .toEqual(['no-restricted-imports'])
    expect(await messages("import { Button } from '@jabiz/ui'\nexport const x = Button\n", 'src/page.ts')).toEqual([])
  })

  it('refuses .ant-* class selectors in the end-to-end tests', async () => {
    expect(await messages("export const a = '.ant-table-row'\n", 'e2e/a.spec.ts')).toEqual(['no-restricted-syntax'])
    expect(await messages('export const a = (n: number) => `.ant-tag:nth-child(${n})`\n', 'e2e/a.spec.ts'))
      .toEqual(['no-restricted-syntax'])
    expect(await messages("export const a = '[data-testid=\"order-row\"]'\n", 'e2e/a.spec.ts')).toEqual([])
  })
})
