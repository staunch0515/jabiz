// The brand profile (docs/quizbuks/plans/Q54-brand-profile.md): one directory per brand under quizbuks-web/brands,
// chosen by QB_BRAND at build time (quizbuks when unset). A brand is deployed on its own (the requester's option A),
// so the profile is read once by the builds and written into what they produce; nothing chooses it at run time.
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

export const BRANDS_DIR = dirname(fileURLToPath(import.meta.url))
export const DEFAULT_BRAND = 'quizbuks'
export const LANGUAGES = ['en', 'zh', 'ja']
/** The files every profile has, besides brand.json. */
export const FILES = ['brand.css', 'mark.svg', 'public/icon-192.png', 'public/icon-512.png', 'public/icon-maskable-512.png']

const COLOUR = /^#[0-9a-f]{6}$/
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** The profiles there are (each a directory with a brand.json). */
export function brandIds() {
  return readdirSync(BRANDS_DIR, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && existsSync(join(BRANDS_DIR, entry.name, 'brand.json')))
    .map((entry) => entry.name)
    .sort()
}

/** The chosen brand's id: QB_BRAND, else quizbuks. */
export function chosenBrand(env = process.env) {
  return env.QB_BRAND?.trim() || DEFAULT_BRAND
}

/** What is wrong with a profile (none when it is complete). */
export function problems(id, profile, exists = (path) => existsSync(join(BRANDS_DIR, id, path))) {
  const found = []
  const text = (value, max) => typeof value === 'string' && value.trim() !== '' && value.length <= max
  if (!text(profile.name, 30)) found.push('name: a text of 1 to 30 characters')
  if (profile.shortName !== undefined && !text(profile.shortName, 12)) found.push('shortName: a text of 1 to 12 characters')
  for (const language of LANGUAGES) {
    if (!text(profile.description?.[language], 120)) found.push(`description.${language}: a text of 1 to 120 characters`)
  }
  for (const key of ['themeColor', 'backgroundColor']) {
    if (!COLOUR.test(profile[key] ?? '')) found.push(`${key}: a colour #rrggbb in lower case`)
  }
  if (!EMAIL.test(profile.supportEmail ?? '')) found.push('supportEmail: an e-mail address')
  if (!/^https:\/\/[^\s/]+$/.test(profile.siteUrl ?? '')) found.push('siteUrl: https://host, without a path')
  for (const file of FILES) {
    if (!exists(file)) found.push(`${file}: missing`)
  }
  return found
}

/**
 * The brand's profile with its directory, checked: a missing or incomplete profile fails the build, saying what
 * is wrong, rather than building an application with another brand's marks.
 */
export function loadBrand(id = chosenBrand()) {
  if (!/^[a-z0-9][a-z0-9-]*$/.test(id) || !brandIds().includes(id)) {
    throw new Error(`QB_BRAND=${id}: no such brand profile in ${BRANDS_DIR} (there are: ${brandIds().join(', ')})`)
  }
  const dir = join(BRANDS_DIR, id)
  const profile = JSON.parse(readFileSync(join(dir, 'brand.json'), 'utf8'))
  const found = problems(id, profile)
  if (found.length > 0) {
    throw new Error(`The brand profile ${id} is incomplete:\n  ${found.join('\n  ')}`)
  }
  return {
    id,
    dir,
    name: profile.name,
    shortName: profile.shortName ?? profile.name,
    description: profile.description,
    themeColor: profile.themeColor,
    backgroundColor: profile.backgroundColor,
    supportEmail: profile.supportEmail,
    siteUrl: profile.siteUrl,
  }
}

/** The part of the profile a page may show (written into the SPAs' code). */
export function publicBrand(brand) {
  const { name, shortName, description, supportEmail, siteUrl } = brand
  return { name, shortName, description, supportEmail, siteUrl }
}
