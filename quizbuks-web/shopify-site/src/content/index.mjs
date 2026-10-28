// The site's pages and languages; the texts of each language live in their own module.
import { en } from './en.mjs'
import { zh } from './zh.mjs'
import { ja } from './ja.mjs'

export const LANGUAGES = ['en', 'zh', 'ja']
export const PAGES = ['home', 'privacy', 'support']

const CONTENT = { en, zh, ja }

export function contentOf(language) {
  const content = CONTENT[language]
  if (!content) throw new Error(`no content for language ${language}`)
  return content
}
