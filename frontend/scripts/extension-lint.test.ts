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

  it('lets only the demo extension keep Ant Design until it moves (phase 15c)', async () => {
    const demo = extensionRulesFor(resolve(import.meta.dirname, '../../backend/app/admin-extension'))
    expect(await lint("import { Button } from 'antd'\nexport const x = Button\n", demo)).toBe(0)
    expect(await lint("import e from 'virtual:jabiz-extension'\nexport const x = e\n", demo)).toBe(1)
    const other = extensionRulesFor(resolve(import.meta.dirname, '../../backend/quizbuks/admin-extension'))
    expect(await lint("import { Button } from 'antd'\nexport const x = Button\n", other)).toBe(1)
  })
})
