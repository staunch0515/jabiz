// The Theme App Extension against the rules `shopify app deploy` enforces (docs/quizbuks/shopify/S2b-ui.md section 3,
// S4c): only the file types Shopify takes in assets/ (no dotfiles: a .gitignore there fails the deploy), each block's
// script within Shopify's 10 000 bytes as written (not gzipped: theme check AssetSizeAppBlockJavaScript), and every
// "t:" key of a block's schema in locales/en.default.schema.json (ValidSchemaTranslations). Run after the build, so
// that a deploy never finds them first.
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { extname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { runInNewContext } from 'node:vm'

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
    let schema
    try {
      schema = JSON.parse(match[1])
    } catch (error) {
      found.push(`blocks/${name}: its schema is not JSON (${error.message})`)
      continue
    }
    if (schema.javascript) {
      const script = join(assets, schema.javascript)
      const size = existsSync(script) ? statSync(script).size : null
      if (size === null) {
        found.push(`blocks/${name}: its script assets/${schema.javascript} is not built`)
      } else if (size > BLOCK_SCRIPT_LIMIT) {
        found.push(`blocks/${name}: assets/${schema.javascript} is ${size} bytes, over Shopify's `
          + `${BLOCK_SCRIPT_LIMIT} for a block's script`)
      }
    }
    for (const key of translationKeys(schema)) {
      if (typeof lookup(texts, key) !== 'string') {
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

/**
 * Whether a built quiz-player.js, run as a classic script, defines the player quiz.js looks for
 * (window.QuizbuksPlayer): quiz.js finds nothing else, and no other test runs the built file.
 */
export function definesPlayer(code) {
  const window = {}
  runInNewContext(code, { window, self: window, globalThis: window })
  return typeof window.QuizbuksPlayer === 'function'
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = fileURLToPath(new URL('../../../tools/quizbuks/shopify/extensions/quizbuks-quiz', import.meta.url))
  const found = problems(dir)
  const player = join(dir, 'assets', 'quiz-player.js')
  if (!existsSync(player) || !definesPlayer(readFileSync(player, 'utf8'))) {
    found.push('assets/quiz-player.js does not define window.QuizbuksPlayer, which quiz.js loads it for')
  }
  for (const problem of found) console.error(problem)
  if (found.length > 0) process.exit(1)
  console.log('the extension meets Shopify\'s rules')
}
