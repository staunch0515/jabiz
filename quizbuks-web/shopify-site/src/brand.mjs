// The brand profile QB_BRAND chooses (docs/quizbuks/plans/Q54-brand-profile.md), read when the site is built: what the
// pages show of it, its mark (an SVG in the --qb-* tokens of its site.css), its stylesheet and its icons.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { loadBrand, publicBrand } from '../../brands/brand.mjs'

const brand = loadBrand()

/** The brand's name, descriptions, support address and site. */
export const BRAND = publicBrand(brand)

/** The brand's mark, an inline SVG (decorative); `className` is given to its <svg>. */
export function brandMark(className) {
  return readFileSync(join(brand.dir, 'mark.svg'), 'utf8').trim().replace(/^<svg /, `<svg class="${className}" `)
}

/** The brand's colours on the static sites, appended to the site's own stylesheet. */
export const BRAND_CSS = readFileSync(join(brand.dir, 'site.css'), 'utf8')

/** The folder of the brand's icons, copied into the site. */
export const BRAND_PUBLIC = join(brand.dir, 'public')
