// Checks of an application's admin extension (decision D22) with this project's tools and configuration:
//   JABIZ_ADMIN_EXTENSION=<dir> node scripts/ext.mjs typecheck|lint|test|check [--if-set]
// The extension has no node_modules or tool configuration of its own; everything comes from here.
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { isAbsolute, resolve } from 'node:path'
import { ESLint } from 'eslint'
import platformConfig from '../eslint.config.js'
import { extensionRules } from './extension-lint.mjs'

const root = resolve(import.meta.dirname, '..')
const value = process.env.JABIZ_ADMIN_EXTENSION
// `--if-set`: part of the platform's own build, where no extension is the usual case.
if (!value && process.argv.includes('--if-set')) process.exit(0)
if (!value) {
  console.error('ext: set JABIZ_ADMIN_EXTENSION to the extension directory')
  process.exit(2)
}
const dir = isAbsolute(value) ? value : resolve(root, value)
if (!existsSync(resolve(dir, 'tsconfig.json'))) {
  console.error(`ext: ${dir} has no tsconfig.json (it extends frontend/tsconfig.extension.json)`)
  process.exit(2)
}
const bin = (name) => resolve(root, 'node_modules/.bin', name)
const run = (command, args) => spawnSync(command, args, { cwd: root, stdio: 'inherit', env: process.env }).status ?? 1

async function lint() {
  const eslint = new ESLint({ cwd: dir, overrideConfigFile: true, overrideConfig: [...platformConfig, extensionRules] })
  const results = await eslint.lintFiles(['src'])
  const formatter = await eslint.loadFormatter('stylish')
  const output = await formatter.format(results)
  if (output) console.log(output)
  return results.some((r) => r.errorCount > 0) ? 1 : 0
}

const steps = {
  typecheck: () => run(bin('tsc'), ['-p', resolve(dir, 'tsconfig.json'), '--noEmit']),
  lint,
  test: () => run(bin('vitest'), ['run', resolve(dir, 'src')]),
}

const wanted = process.argv[2] === 'check' ? Object.keys(steps) : [process.argv[2]]
let status = 0
for (const step of wanted) {
  if (!steps[step]) {
    console.error(`ext: unknown step '${step}' (typecheck, lint, test, check)`)
    process.exit(2)
  }
  const code = await steps[step]()
  if (code !== 0) status = code
}
process.exit(status)
