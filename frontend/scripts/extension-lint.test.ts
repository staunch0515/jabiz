// @vitest-environment node
import { ESLint } from 'eslint'
import { describe, expect, it } from 'vitest'
import platformConfig from '../eslint.config.js'
import { extensionRules } from './extension-lint.mjs'

async function lint(code: string) {
  const eslint = new ESLint({ overrideConfigFile: true, overrideConfig: [...platformConfig, extensionRules] })
  const [result] = await eslint.lintText(code, { filePath: 'src/page.ts' })
  return result.messages.filter((m) => m.ruleId === 'no-restricted-imports').length
}

describe('extension lint rules (decision D22)', () => {
  it('allows the platform through @jabiz/admin and third-party packages', async () => {
    expect(await lint("import { api } from '@jabiz/admin'\nimport { Button } from 'antd'\nexport const x = [api, Button]\n")).toBe(0)
  })

  it('refuses imports of the platform frontend and of the extension loader', async () => {
    expect(await lint("import { api } from '../../../frontend/src/api/client'\nexport const x = api\n")).toBe(1)
    expect(await lint("import e from 'virtual:jabiz-extension'\nexport const x = e\n")).toBe(1)
  })
})
