// Writes the maintenance page for the brand QB_BRAND chooses (Q55): `node quizbuks-web/maintenance/build.mjs [file]`,
// by default deploy/quizbuks/server/maintenance.html, the file the deploy workflow copies to the server. The committed
// file is QuizBuks's; maintenance.test.mjs checks it is up to date.
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadBrand, publicBrand } from '../brands/brand.mjs'
import { renderPage } from './page.mjs'

const here = dirname(fileURLToPath(import.meta.url))
export const DEFAULT_OUT = resolve(here, '../../deploy/quizbuks/server/maintenance.html')

/** The page of a brand profile. */
export function maintenancePage(brand = loadBrand()) {
  return renderPage({
    brand: publicBrand(brand),
    mark: readFileSync(join(brand.dir, 'mark.svg'), 'utf8'),
    css: readFileSync(join(brand.dir, 'site.css'), 'utf8'),
    icon: readFileSync(join(brand.dir, 'public', 'icon-192.png')),
  })
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const out = resolve(process.argv[2] ?? DEFAULT_OUT)
  writeFileSync(out, maintenancePage())
  console.log(`maintenance page (${loadBrand().id}) → ${out}`)
}
