// The brand profile in a QuizBuks SPA's Vite build (docs/quizbuks/plans/Q54-brand-profile.md): the profile's icons
// are the SPA's public files, @qb-brand/… its brand.css and mark.svg, __QB_BRAND__ what pages show of it, and
// __QB_BRAND_NAME__ in index.html its name.
import { join } from 'node:path'
import { loadBrand, publicBrand } from './brand.mjs'

const HTML_ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }

function escapeHtml(text) {
  return text.replace(/[&<>"']/g, (character) => HTML_ESCAPES[character])
}

/**
 * The plugin; `allow` is what the dev server may serve besides the profile (Vite's workspace root, which an explicit
 * list replaces: pass searchForWorkspaceRoot(process.cwd())).
 */
export function qbBrand({ brand = loadBrand(), allow = [] } = {}) {
  return {
    name: 'qb-brand',
    config: () => ({
      publicDir: join(brand.dir, 'public'),
      resolve: { alias: { '@qb-brand': brand.dir } },
      define: { __QB_BRAND__: JSON.stringify(publicBrand(brand)) },
      server: { fs: { allow: [...allow, brand.dir] } },
    }),
    transformIndexHtml: (html) => html.replaceAll('__QB_BRAND_NAME__', escapeHtml(brand.name)),
  }
}
