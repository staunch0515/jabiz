// The Theme App Extension against the rules `shopify app deploy` enforces (docs/quizbuks/shopify/S2b-ui.md section 3,
// S4c): only the file types Shopify takes in assets/ (no dotfiles: a .gitignore there fails the deploy), each block's
// script within Shopify's 10 000 bytes as written (not gzipped: theme check AssetSizeAppBlockJavaScript), and every
// "t:" key of a block's schema in locales/en.default.schema.json (ValidSchemaTranslations). Run after the build, so
// that a deploy never finds them first.
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { extname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/** The file types Shopify accepts in a Theme App Extension's assets. */
export const ASSET_TYPES = ['.jpg', '.jpeg', '.json', '.js', '.css', '.png', '.svg', '.wasm']
/** The largest script an app block may name, in bytes (Shopify's theme check). */
export const BLOCK_SCRIPT_LIMIT = 10_000

/** What is wrong with the extension in `dir`; empty when a deploy would take it. */
export function problems(dir) {
  const found = []
  const assets = join(dir, 'assets')
  for (const name of existsSync(assets) ? readdirSync(assets) : []) {
    if (name.startsWith('.') || !ASSET_TYPES.includes(extname(name).toLowerCase())) {
      found.push(`assets/${name}: Shopify takes only ${ASSET_TYPES.join(', ')} in assets`)
    }
  }
  const schemaTexts = join(dir, 'locales', 'en.default.schema.json')
  const texts = existsSync(schemaTexts) ? JSON.parse(readFileSync(schemaTexts, 'utf8')) : {}
  const blocks = join(dir, 'blocks')
  for (const name of existsSync(blocks) ? readdirSync(blocks).filter((n) => n.endsWith('.liquid')) : []) {
    const liquid = readFileSync(join(blocks, name), 'utf8')
    const match = liquid.match(/\{%-?\s*schema\s*-?%\}([\s\S]*?)\{%-?\s*endschema\s*-?%\}/)
    if (!match) continue
    const schema = JSON.parse(match[1])
    if (schema.javascript) {
      const script = join(assets, schema.javascript)
      if (!existsSync(script)) {
        found.push(`blocks/${name}: its script assets/${schema.javascript} is not built`)
      } else if (statSync(script).size > BLOCK_SCRIPT_LIMIT) {
        found.push(`blocks/${name}: assets/${schema.javascript} is ${statSync(script).size} bytes, over Shopify's `
          + `${BLOCK_SCRIPT_LIMIT} for a block's script`)
      }
    }
    for (const key of translationKeys(schema)) {
      if (lookup(texts, key) === undefined) {
        found.push(`blocks/${name}: "t:${key}" has no entry in locales/en.default.schema.json`)
      }
    }
  }
  return found
}

function translationKeys(value) {
  if (typeof value === 'string') return value.startsWith('t:') ? [value.slice(2)] : []
  if (Array.isArray(value)) return value.flatMap(translationKeys)
  if (value && typeof value === 'object') return Object.values(value).flatMap(translationKeys)
  return []
}

function lookup(texts, key) {
  return key.split('.').reduce((node, part) => (node && typeof node === 'object' ? node[part] : undefined), texts)
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = fileURLToPath(new URL('../../../tools/quizbuks/shopify/extensions/quizbuks-quiz', import.meta.url))
  const found = problems(dir)
  for (const problem of found) console.error(problem)
  if (found.length > 0) process.exit(1)
  console.log('the extension meets Shopify\'s rules')
}
