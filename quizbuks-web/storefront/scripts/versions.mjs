// The block's build and test tools at the platform frontend's versions (decision D34 item 5). It uses only
// @jabiz/ui/testing, not React or @jabiz/ui's components, so `pnpm app:check` (made for SPAs on @jabiz/ui) asks for
// what it does not use; this checks what it does, with app:check's own reading of the lockfiles.
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { platformVersions, pnpmImporterVersions } from '../../../frontend/scripts/app-check.mjs'

const here = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const platform = platformVersions(resolve(here, '../../frontend'))
const own = pnpmImporterVersions(readFileSync(resolve(here, 'pnpm-lock.yaml'), 'utf8'))
const wanted = new Map([['vite', platform.shared.get('vite')], ...platform.testing])
const problems = []
for (const [name, version] of wanted) {
  const mine = own.get(name)
  if (mine !== undefined && mine !== version) problems.push(`${name}: ${mine}, the platform's is ${version}`)
}
if (problems.length > 0) {
  console.error(`versions: the storefront block differs from the platform's frontend:\n- ${problems.join('\n- ')}`)
  process.exit(1)
}
console.log('versions: the storefront block uses the platform\'s versions')
