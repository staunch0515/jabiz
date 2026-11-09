import { fetchFileContent, runProcess, uploadFile } from '@jabiz/client'
import { parseStoredTranslations, type TextsByLanguage } from '../content/TextTranslationsFields'
import { base64Of, blobOf, withoutData } from '../content/transfer'
import type { MySponsorPage } from '../page/api'
import { SPONSOR_PAGE } from '../page/ids'
import type { MyProduct } from '../products/api'
import type { MailServer } from '../publishing/api'
import { CONTENT_LANGUAGES, type ContentLanguage, type Sponsor } from '../qb/api'
import { FILE_POLICY } from '../qb/ids'
import { PROFILE_TRANSFER } from './ids'

/**
 * Profile packages (docs/quizbuks/plans/Q100-profile-package.md): the sponsor's basics, shop page, products and mail
 * server settings (never its password) in one JSON file, and back. The export is the console's own (it reads what the
 * hub's pages read, and the images); the import asks the server what it would do (`QB_PROFILE_IMPORT_CHECK`),
 * uploads the images it needs through their policies, then imports in one transaction (`QB_PROFILE_IMPORT`). An
 * import never changes a review (D-Q100-3).
 */

export const PROFILE_PACKAGE_FORMAT = 'quizbuks-profile'
export const PROFILE_PACKAGE_SCHEMA = 1

/** Texts by language, the package's first language being the base. */
export type Texts = Partial<Record<ContentLanguage, string>>
/** An image: a file's key in `files`, a link, or none. */
export type PackageImage = string | { url: string } | null

export interface PackageFile {
  name: string
  type: string
  size: number
  /** The bytes, base64. */
  data: string
}

export interface ProfilePackageDocument {
  format: typeof PROFILE_PACKAGE_FORMAT
  schema: number
  exportedAt: string
  languages: ContentLanguage[]
  basics?: { contactName: string; company: string; intro: Texts; avatar: PackageImage }
  page?: { handle: string; headline: Texts; website: string | null; cover: PackageImage }
  products: {
    productId: string; code?: string; name: Texts; description: Texts; price: string; currency: string; shopUrl: string
    listed: boolean; image: PackageImage
  }[]
  mailServer?: { host: string; port: number; tls: string; username: string; fromAddress: string
    fromName: string | null }
  files: Record<string, PackageFile>
}

export interface ProfileSources {
  sponsor: Sponsor | null
  page: MySponsorPage | null
  products: MyProduct[]
  mailServer: MailServer | null
  /** The language of the texts when there is no page yet (the interface's). */
  fallbackLanguage: ContentLanguage
}

const EXTENSIONS: Record<string, string> = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp',
  'image/gif': 'gif' }

/** The texts of a field: the base text and its stored translations. */
function textsOf(base: ContentLanguage, text: string | null | undefined, translations: TextsByLanguage,
  field: string): Texts {
  const texts: Texts = {}
  if (text) texts[base] = text
  for (const language of CONTENT_LANGUAGES) {
    const translated = translations[language]?.[field]
    if (language !== base && translated) texts[language] = translated
  }
  return texts
}

/** The profile as a package, its images keyed `f1`, `f2` … in the order they are first used. */
export async function exportProfile(sources: ProfileSources, now: Date = new Date(),
  read: (fileId: string) => Promise<Blob> = (id) => fetchFileContent(id)): Promise<ProfilePackageDocument> {
  const { sponsor, page, products, mailServer } = sources
  const base: ContentLanguage = page?.language ?? sources.fallbackLanguage
  const files: Record<string, PackageFile> = {}
  const keys = new Map<string, string>()
  const image = async (fileId: string | null): Promise<PackageImage> => {
    if (!fileId) return null
    if (!keys.has(fileId)) {
      const blob = await read(fileId)
      const key = `f${keys.size + 1}`
      keys.set(fileId, key)
      const type = blob.type || 'image/jpeg'
      files[key] = { name: `image.${EXTENSIONS[type] ?? 'img'}`, type, size: blob.size, data: await base64Of(blob) }
    }
    return keys.get(fileId)!
  }
  const used = new Set<ContentLanguage>([base])
  const note = (texts: Texts) => (Object.keys(texts) as ContentLanguage[]).forEach((l) => used.add(l))
  page?.languages.forEach((l) => used.add(l))

  const document: ProfilePackageDocument = {
    format: PROFILE_PACKAGE_FORMAT, schema: PROFILE_PACKAGE_SCHEMA, exportedAt: now.toISOString(),
    languages: [], products: [], files,
  }
  if (sponsor) {
    const intro = textsOf(base, sponsor.intro, parseStoredTranslations(sponsor.introTranslations).texts, 'intro')
    note(intro)
    document.basics = { contactName: sponsor.contactName, company: sponsor.company, intro,
      avatar: await image(sponsor.avatar) }
  }
  if (page) {
    const headline = textsOf(base, page.headline, page.translations, 'headline')
    note(headline)
    document.page = { handle: page.handle, headline, website: page.website, cover: await image(page.cover) }
  }
  for (const product of products) {
    const name = textsOf(base, product.name, product.translations, 'name')
    const description = textsOf(base, product.description, product.translations, 'description')
    note(name)
    note(description)
    document.products.push({ productId: product.productId, ...(product.code ? { code: product.code } : {}), name,
      description, price: product.price,
      currency: product.currency, shopUrl: product.shopUrl, listed: product.status !== 'UNLISTED',
      image: await image(product.image) })
  }
  if (mailServer) {
    document.mailServer = { host: mailServer.host, port: mailServer.port, tls: mailServer.tls,
      username: mailServer.username, fromAddress: mailServer.fromAddress, fromName: mailServer.fromName }
  }
  document.languages = [base, ...CONTENT_LANGUAGES.filter((l) => l !== base && used.has(l))]
  return document
}

/** The file name of an export: the page's handle (or the company) and the day. */
export function packageFileName(document: ProfilePackageDocument): string {
  const name = (document.page?.handle ?? document.basics?.company ?? 'profile').trim()
    .replace(/[\\/:*?"<>|\s]+/g, '-').slice(0, 60) || 'profile'
  return `${name}-${document.exportedAt.slice(0, 10)}.quizbuks-profile.json`
}

/** What the import does with a part (`ProfileTransferProcesses`). */
export type PartAction = 'SAVE' | 'NONE' | 'APPLY_FORM' | 'PENDING' | 'NOT_APPROVED' | 'PASSWORD'

export interface FileNeed { path: string; key: string; policy: string; type: string; size: number }

/** `ProfileTransferProcesses.Plan`. */
export interface ImportPlan {
  basics: PartAction
  page: PartAction
  added: string[]
  changed: string[]
  deleted: string[]
  mailServer: PartAction
  files: FileNeed[]
  links: { path: string; url: string }[]
  /** D-Q100-13: products the import deletes that quizzes show, with those quizzes' titles. */
  linked: { product: string; quizzes: string[] }[]
}

/** What the server would do with the package; a refusal (its problems) throws. */
export async function checkProfileImport(document: Record<string, unknown>): Promise<ImportPlan> {
  const output = await runProcess<{ plan: ImportPlan }>(PROFILE_TRANSFER.check, { document: withoutData(document) })
  return output.plan
}

const KNOWN_POLICIES = new Set<string>([FILE_POLICY.sponsorAvatar, SPONSOR_PAGE.imagePolicy])

/**
 * Uploads the images the plan needs, each through its policy; their paths with the uploaded ids. F-C4's sponsor
 * package imports the same profile part through its own import record.
 */
export async function uploadProfileImages(document: Record<string, unknown>, plan: ImportPlan,
  onProgress?: (done: number, total: number) => void): Promise<Record<string, string>> {
  const files = (document.files ?? {}) as Record<string, Partial<PackageFile>>
  const uploaded: Record<string, string> = {}
  let done = 0
  onProgress?.(done, plan.files.length)
  for (const need of plan.files) {
    const entry = files[need.key]
    if (!KNOWN_POLICIES.has(need.policy) || !entry?.data) throw new Error(`No image for ${need.path}`)
    const type = entry.type ?? need.type
    const { fileId } = await uploadFile(need.policy, blobOf(entry.data, type), `image.${EXTENSIONS[type] ?? 'img'}`)
    if (!fileId) throw new Error(`No image for ${need.path}`)
    uploaded[need.path] = fileId
    onProgress?.(++done, plan.files.length)
  }
  return uploaded
}

/** Uploads the images the plan needs, then imports; a refusal throws and nothing is changed. */
export async function importProfile(document: Record<string, unknown>, plan: ImportPlan, mailPassword: string,
  onProgress?: (done: number, total: number) => void): Promise<ImportPlan> {
  const uploaded = await uploadProfileImages(document, plan, onProgress)
  const output = await runProcess<{ plan: ImportPlan }>(PROFILE_TRANSFER.import, {
    document: withoutData(document), files: uploaded, mailPassword: mailPassword || null,
  })
  return output.plan
}

const APPLY_DRAFT_KEY = 'qb.sponsor.apply-draft'

/** The basics of a package for the application form (D-Q100-3 ①): kept in this tab until the form takes them. */
export interface ApplyDraft { contactName: string; company: string; intro: string; introTranslations: TextsByLanguage }

export function keepApplyDraft(document: Record<string, unknown>) {
  const basics = document.basics as ProfilePackageDocument['basics'] | undefined
  const languages = (document.languages as ContentLanguage[] | undefined) ?? []
  if (!basics || languages.length === 0) return
  const base = languages[0]
  const intro = typeof basics.intro === 'string' ? { [base]: basics.intro } as Texts : basics.intro ?? {}
  const introTranslations: TextsByLanguage = {}
  for (const language of languages.slice(1)) {
    if (intro[language]) introTranslations[language] = { intro: intro[language]! }
  }
  const draft: ApplyDraft = { contactName: basics.contactName ?? '', company: basics.company ?? '',
    intro: intro[base] ?? '', introTranslations }
  try {
    sessionStorage.setItem(APPLY_DRAFT_KEY, JSON.stringify(draft))
  } catch {
    // Without storage the form starts empty, as before.
  }
}

/** The kept basics, once: the form takes them and they are gone. */
export function takeApplyDraft(): ApplyDraft | null {
  try {
    const kept = sessionStorage.getItem(APPLY_DRAFT_KEY)
    if (!kept) return null
    sessionStorage.removeItem(APPLY_DRAFT_KEY)
    return JSON.parse(kept) as ApplyDraft
  } catch {
    return null
  }
}
