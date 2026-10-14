// @vitest-environment node
import { resolve } from 'node:path'
import { ESLint, type Linter } from 'eslint'
import { describe, expect, it } from 'vitest'
import platformConfig from '../eslint.config.js'
import { extensionRules, extensionRulesFor } from './extension-lint.mjs'

async function lint(code: string, rules: Linter.Config = extensionRules) {
  const eslint = new ESLint({ overrideConfigFile: true, overrideConfig: [...platformConfig, rules] })
  const [result] = await eslint.lintText(code, { filePath: 'src/page.ts' })
  return result.messages.filter((m) => m.ruleId === 'no-restricted-imports').length
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
