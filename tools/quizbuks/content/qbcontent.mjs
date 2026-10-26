#!/usr/bin/env node
// The content tool (docs/quizbuks/plans/Q36-content-pack.md): keeps a sponsor's quizzes and publications as a content
// pack (files) and writes them into any QuizBuks through the same API the sponsor console uses, so every rule is
// checked as for a person (review, the reward cap, sealing, the audit). No database access, no third-party package.
//
//   node qbcontent.mjs export --base URL --user EMAIL --pack DIR      a sponsor's quizzes and publications into a pack
//   node qbcontent.mjs apply  --base URL --user EMAIL --pack DIR      the pack into the sponsor's account (skips what exists)
//   node qbcontent.mjs renew  --base URL --user EMAIL --pack DIR      a new period for publications ending soon
//   node qbcontent.mjs takers --base URL --environment NAME --count N --mailpit URL --pack DIR   test systems only: invented takers answer
//
// The password is read from the environment variable QB_PASSWORD (never an argument: it would stay in the shell's
// history). --dry-run says what would be done and writes nothing.
import { randomUUID } from 'node:crypto'
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { basename, join } from 'node:path'
import {
  PACK_FORMAT, PACK_VERSION, missingContent, neutralName, packProblems, publicationAction, publicationSpecOf, quizFileName, quizFileOf,
  saveInputOf, withoutData,
} from './lib.mjs'

const DATASET = {
  quiz: 'urn:jabiz:dataset:sponsor:QbQuiz', question: 'urn:jabiz:dataset:sponsor:QbQuestion',
  option: 'urn:jabiz:dataset:sponsor:QbOption', material: 'urn:jabiz:dataset:sponsor:QbMaterial',
  materialImage: 'urn:jabiz:dataset:sponsor:QbMaterialImage', publication: 'urn:jabiz:dataset:sponsor:QbPublication',
  tier: 'urn:jabiz:dataset:sponsor:QbRewardTier', sponsor: 'urn:jabiz:dataset:sponsor:QbSponsor',
}
const POLICY = { image: 'qb.content.image', pdf: 'qb.content.pdf', audio: 'qb.content.audio' }
const PAGE = 100

function args(argv) {
  const [command, ...rest] = argv
  const options = { command }
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i]
    if (!a.startsWith('--')) throw new Error(`unexpected argument ${a}`)
    const name = a.slice(2)
    if (name === 'dry-run') options.dryRun = true
    else options[name] = rest[++i]
  }
  return options
}

/** A client of one QuizBuks for one signed-in user of an entry. */
class Client {
  constructor(base) {
    this.base = base.replace(/\/+$/, '')
    this.token = null
  }

  async http(method, path, body, headers = {}) {
    const response = await fetch(this.base + path, {
      method,
      headers: { ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
        ...(body !== undefined && !(body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}), ...headers },
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
    })
    return response
  }

  async json(method, path, body, headers) {
    const response = await this.http(method, path, body, headers)
    const text = await response.text()
    const value = text ? JSON.parse(text) : null
    if (!response.ok) {
      const error = new Error(`${method} ${path}: ${response.status} ${value?.code ?? ''} ${value?.detail ?? text}`.trim())
      error.status = response.status
      error.body = value
      throw error
    }
    return value
  }

  async signIn(user, password, entry) {
    const out = await this.json('POST', '/api/auth/login', { userName: user, password, entry })
    this.token = out.accessToken
  }

  /** The system's name the server gives (platform 16zd, `jabiz.web.environment`): `production` or a test system's. */
  async environment(entry) {
    const out = await this.json('GET', `/api/auth/entries/${entry}`)
    return typeof out?.environment === 'string' ? out.environment : null
  }

  process(name, input) {
    return this.json('POST', `/api/processes/${name}/latest`, input, { 'Idempotency-Key': randomUUID() })
      .then((out) => out.output)
  }

  async query(id, params = {}, extra = {}) {
    const items = []
    for (let offset = 0; ; offset += PAGE) {
      const page = await this.json('POST', `/api/queries/${id}`, { params, offset, limit: PAGE, ...extra })
      items.push(...(page.items ?? []))
      if ((page.items ?? []).length < PAGE) return items
    }
  }

  async rows(dataset, filters = [], sorts = [{ field: 'seq', asc: true }]) {
    const rows = []
    for (let offset = 0; ; offset += PAGE) {
      const page = await this.json('POST', `/api/datasets/${encodeURIComponent(dataset)}/query`,
        { offset, limit: PAGE, filters, sorts })
      for (const item of page.items ?? []) rows.push(item.attributes ?? {})
      if ((page.items ?? []).length < PAGE) return rows
    }
  }

  async entity(dataset, id) {
    const response = await this.http('GET', `/api/datasets/${encodeURIComponent(dataset)}/entities/${encodeURIComponent(id)}`)
    if (response.status === 404) return null
    if (!response.ok) throw new Error(`entity ${dataset}/${id}: ${response.status}`)
    return (await response.json()).attributes ?? {}
  }

  async download(fileId) {
    const response = await this.http('GET', `/api/files/${encodeURIComponent(fileId)}/content`)
    if (!response.ok) throw new Error(`file ${fileId}: ${response.status}`)
    return { type: response.headers.get('content-type')?.split(';')[0] ?? 'application/octet-stream',
      bytes: Buffer.from(await response.arrayBuffer()) }
  }

  /** Uploads one file, waiting out the upload limit (429) a few times. */
  async upload(policy, bytes, type, name) {
    for (let attempt = 0; ; attempt++) {
      const form = new FormData()
      form.append('file', new Blob([bytes], { type }), name)
      const response = await this.http('POST', `/api/files?policy=${encodeURIComponent(policy)}`, form)
      if (response.status === 429 && attempt < 5) {
        await new Promise((resolve) => setTimeout(resolve, 20_000))
        continue
      }
      const text = await response.text()
      if (!response.ok) throw new Error(`upload: ${response.status} ${text}`)
      return JSON.parse(text).fileId
    }
  }
}

const log = (...line) => console.log(...line)

async function signedIn(options, entry = 'sponsor') {
  if (!options.base) throw new Error('--base is required (for example https://staging.quizbuks.com)')
  if (!options.user) throw new Error('--user is required (the sponsor\'s e-mail address)')
  const password = process.env.QB_PASSWORD
  if (!password) throw new Error('set the password in the environment variable QB_PASSWORD')
  const client = new Client(options.base)
  await client.signIn(options.user, password, entry)
  return client
}

async function sponsorOf(client) {
  const rows = await client.rows(DATASET.sponsor, [], [])
  return rows[0] ?? null
}

// ---- export ------------------------------------------------------------------------------------------------------

async function exportPack(options) {
  if (!options.pack) throw new Error('--pack is required (the folder the pack is written to)')
  const client = await signedIn(options)
  const sponsor = await sponsorOf(client)
  const quizzes = await client.query('qb.sponsor.quizzes')
  if (!options.dryRun) await mkdir(join(options.pack, 'quizzes'), { recursive: true })
  const fileOfQuiz = new Map()
  const used = new Set()
  for (const row of quizzes) {
    const quizId = String(row.quizId)
    const quiz = await client.entity(DATASET.quiz, quizId)
    if (!quiz) continue
    const filter = [{ field: 'quizId', op: 'eq', value: quizId }]
    const [questions, options_, materials, images] = await Promise.all([
      client.rows(DATASET.question, filter), client.rows(DATASET.option, filter), client.rows(DATASET.material, filter),
      client.rows(DATASET.materialImage, filter)])
    const { document, files } = quizFileOf({ quiz, questions, options: options_, materials, images }, new Date().toISOString())
    for (const f of files) {
      const { type, bytes } = await client.download(f.fileId)
      document.files[f.key] = { name: neutralName(f.kind, type), type, size: bytes.length, data: bytes.toString('base64') }
    }
    let name = quizFileName(document.quiz.title)
    for (let n = 2; used.has(name); n++) name = quizFileName(`${document.quiz.title} ${n}`)
    used.add(name)
    fileOfQuiz.set(quizId, `quizzes/${name}`)
    if (!options.dryRun) await writeFile(join(options.pack, 'quizzes', name), JSON.stringify(document, null, 1))
    log(`quiz      ${document.quiz.title}  →  quizzes/${name}  (${files.length} files)`)
  }
  const publications = []
  // Newest first: of several publications of a name (renewed periods, a corrected copy) the pack keeps the latest settings.
  for (const row of await client.rows(DATASET.publication, [], [{ field: 'editedAt', asc: false }])) {
    const file = row.quizId ? fileOfQuiz.get(String(row.quizId)) : null
    if (!file) {
      log(`skipped   publication ${row.name}: its quiz is not in the pack`)
      continue
    }
    if (publications.some((p) => p.name === row.name)) continue
    const tiers = await client.rows(DATASET.tier, [{ field: 'publicationId', op: 'eq', value: String(row.publicationId) }])
    publications.push(publicationSpecOf(row, tiers, file))
    log(`publication ${row.name}`)
  }
  const pack = {
    format: PACK_FORMAT,
    formatVersion: PACK_VERSION,
    exportedAt: new Date().toISOString(),
    exportedFrom: client.base,
    sponsor: sponsor ? { displayName: sponsor.contactName ?? null, company: sponsor.company ?? null,
      intro: sponsor.intro ?? null } : null,
    publications,
  }
  if (!options.dryRun) await writeFile(join(options.pack, 'content.json'), JSON.stringify(pack, null, 2) + '\n')
  log(`\n${quizzes.length} quizzes and ${publications.length} publications ${options.dryRun ? 'would be' : 'were'} written to ${options.pack}`)
}

// ---- apply and renew --------------------------------------------------------------------------------------------

async function readPack(dir) {
  const pack = JSON.parse(await readFile(join(dir, 'content.json'), 'utf8'))
  const files = (await readdir(join(dir, 'quizzes')).catch(() => [])).filter((f) => f.endsWith('.json'))
    .map((f) => `quizzes/${f}`)
  const problems = packProblems(pack, files)
  if (problems.length) throw new Error(`the pack cannot be applied:\n - ${problems.join('\n - ')}`)
  return { pack, files }
}

/** The quiz of a file in the sponsor's account (by its title), imported and versioned when it is not there. */
async function quizOf(client, dir, file, known, dryRun) {
  const document = JSON.parse(await readFile(join(dir, file), 'utf8'))
  const title = document.quiz?.title
  const found = known.find((q) => q.title === title)
  if (found) return { quizId: String(found.quizId), title, created: false }
  if (dryRun) return { quizId: null, title, created: true }
  const check = await client.process('QB_QUIZ_IMPORT_CHECK', { document: withoutData(document) })
  const uploaded = {}
  for (const need of check.files) {
    const entry = document.files[need.key]
    const kind = need.policy === POLICY.pdf ? 'pdf' : need.policy === POLICY.audio ? 'audio' : 'image'
    uploaded[need.key] = String(await client.upload(need.policy, Buffer.from(entry.data, 'base64'), need.type,
      neutralName(kind, need.type)))
  }
  const imported = await client.process('QB_QUIZ_IMPORT', { document: withoutData(document), files: uploaded })
  return { quizId: String(imported.quizId), title, created: true }
}

/**
 * The version a new publication of the quiz uses: its newest existing one. A version is saved only for a quiz with
 * none (one the tool just imported): never from edits the sponsor has under way in the console.
 */
async function versionOf(client, quiz) {
  const newest = async () => (await client.query('qb.sponsor.quiz-versions', { quizId: quiz.quizId }))
    .reduce((top, v) => (!top || Number(v.versionNo) > Number(top.versionNo) ? v : top), null)
  let version = await newest()
  if (!version) {
    await client.process('QB_QUIZ_PUBLISH_VERSION', { quizId: quiz.quizId })
    log(`versioned quiz ${quiz.title}`)
    version = await newest()
  }
  return String(version.versionId)
}

async function applyPack(options, { renew = false } = {}) {
  if (!options.pack) throw new Error('--pack is required (the folder of the pack)')
  const { pack, files } = await readPack(options.pack)
  // A shared prize's code comes from the environment: all of them are there before anything is written.
  const missing = missingContent(pack, process.env)
  if (missing.length) throw new Error(`the pack cannot be applied:\n - ${missing.join('\n - ')}`)
  const client = await signedIn(options)
  const sponsor = await sponsorOf(client)
  if (sponsor?.reviewStatus !== 'APPROVED') {
    throw new Error('the account is not an approved sponsor yet: sign in to the sponsor console, apply and wait for the review')
  }
  const renewDays = renew ? Number(options['renew-days'] ?? 30) : 0
  const now = new Date()
  const known = await client.query('qb.sponsor.quizzes')
  const mine = await client.rows(DATASET.publication, [], [{ field: 'editedAt', asc: true }])
  // Every quiz of the pack, with or without a publication.
  const quizzes = new Map()
  for (const file of files) {
    const quiz = await quizOf(client, options.pack, file, known, options.dryRun)
    if (quiz.created) log(`${options.dryRun ? 'would import' : 'imported'}  quiz ${quiz.title}`)
    quizzes.set(file, quiz)
  }
  let made = 0
  for (const spec of pack.publications) {
    const action = publicationAction(mine.filter((p) => p.name === spec.name), { now, renewDays })
    if (action.action === 'skip') {
      log(`skipped   ${spec.name} (${action.status})`)
      continue
    }
    const quiz = quizzes.get(spec.quiz)
    const startAt = action.action === 'renew' ? action.after.toISOString() : undefined
    if (options.dryRun) {
      log(`would ${action.action === 'renew' ? 'renew' : 'publish'}  ${spec.name}${startAt ? ` from ${startAt}` : ''}`)
      continue
    }
    // A renewal keeps the running period's version (lib.publicationAction).
    const versionId = action.versionId ? String(action.versionId) : await versionOf(client, quiz)
    const input = saveInputOf(startAt ? { ...spec, startAt } : spec, versionId, now, process.env)
    const saved = await client.process('QB_PUBLICATION_SAVE', input)
    const problems = saved.problems ?? []
    if (problems.length) {
      const lines = problems.map((p) => `${p.field} ${p.code}: ${p.message}`).join('\n - ')
      log(`draft     ${spec.name}: saved as a draft, not submitted:\n - ${lines}`)
      continue
    }
    try {
      const submitted = await client.process('QB_PUBLICATION_SUBMIT', { publicationId: saved.publicationId,
        baseRevision: saved.revision })
      log(`${action.action === 'renew' ? 'renewed' : 'published'} ${spec.name}: ${submitted.status ?? submitted.approval ?? 'submitted'}`)
      made++
    } catch (e) {
      // Terms to accept are the person's own act: the tool never accepts them on anybody's behalf.
      if (e.body?.code === 'QB_CONSENT_REQUIRED' || e.body?.code === 'QB_TERMS_OUTDATED') {
        throw new Error('the sponsor terms changed: sign in to the sponsor console, accept them, then run this again')
      }
      throw e
    }
  }
  if (!options.dryRun && pack.sponsor && options['with-profile']) {
    await client.process('QB_SPONSOR_PROFILE_SAVE', { displayName: pack.sponsor.displayName, company: pack.sponsor.company,
      intro: pack.sponsor.intro, avatar: sponsor.avatar ?? null, baseRevision: sponsor.revision ?? null })
    log('updated   the sponsor profile')
  }
  log(`\n${made} publication(s) ${renew ? 'renewed or ' : ''}made; the rest existed already`)
}

// ---- invented takers (test systems only) ------------------------------------------------------------------------

async function mailLink(mailpit, email, path) {
  for (let attempt = 0; attempt < 30; attempt++) {
    const list = await (await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}`)).json()
    const id = list.messages?.[0]?.ID
    if (id) {
      const message = await (await fetch(`${mailpit}/api/v1/message/${id}`)).json()
      // Addresses end before spaces, quotes and closing brackets (as the end-to-end tests read them).
      const link = [...(message.Text ?? '').matchAll(/https?:\/\/[^\s<>"')\]]+/g)]
        .map((m) => new URL(m[0].replace(/&amp;/g, '&'))).find((url) => url.pathname.endsWith(path))
      if (link) return link
    }
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  throw new Error(`no mail with ${path} reached ${email}`)
}

async function takers(options) {
  if (!options.base || !options.mailpit) throw new Error('--base and --mailpit are required')
  const count = Number(options.count ?? 5)
  const probe = new Client(options.base)
  const environment = await probe.environment('app')
  // Invented people never go into the real system. A server without a name says `test`, so the name alone does not
  // tell a test system from a real one set up without it: the operator names the system and it must match.
  if (!options.environment) {
    throw new Error(`--environment is required: the name of the test system (this server says ${JSON.stringify(environment)})`)
  }
  if (!environment || environment.toLowerCase() === 'production' || options.environment.toLowerCase() === 'production'
    || environment !== options.environment) {
    throw new Error(`refused: the server says it is ${JSON.stringify(environment)}, not the test system ${JSON.stringify(options.environment)}; invented takers are for test systems only`)
  }
  const names = options.pack ? (JSON.parse(await readFile(join(options.pack, 'content.json'), 'utf8')).publications ?? [])
    .map((p) => p.name) : null
  const password = `taker-${randomUUID()}`
  for (let n = 1; n <= count; n++) {
    const email = `taker-${Date.now()}-${n}@example.test`
    const client = new Client(options.base)
    await client.json('POST', '/api/auth/register', { email, password, displayName: `Taker ${n}`, locale: 'en', entry: 'app' })
    const token = (await mailLink(options.mailpit, email, '/auth/verify-email')).searchParams.get('token')
    const check = await client.json('POST', '/api/auth/verify-email/check', { token })
    await client.json('POST', '/api/auth/verify-email', check.passwordRequired ? { token, password } : { token })
    await client.signIn(email, password, 'app')
    const consents = await client.query('qb.me.consents', { kinds: ['TERMS_APP', 'PRIVACY'], at: new Date().toISOString() })
    const version = (kind) => consents.find((c) => c.kind === kind)?.versionNo ?? null
    await client.process('QB_PROFILE_SAVE', { displayName: `Taker ${n}`, countryCode: options.country ?? 'JP',
      preferredCurrency: 'JPY', acceptTerms: true, termsVersionNo: version('TERMS_APP'), privacyVersionNo: version('PRIVACY') })
    const feed = await client.query('qb.app.feed', { at: new Date().toISOString(), country: options.country ?? 'JP' })
    let answered = 0
    for (const card of feed.filter((c) => !names || names.includes(c.name))) {
      try {
        const started = await client.process('QB_ATTEMPT_START', { publicationId: card.publicationId })
        // Each invented taker answers at random, so the counts and the analytics have something to show.
        const answers = started.questions.map((q) => ({ questionNo: q.no,
          selected: q.options.length ? [q.options[Math.floor(Math.random() * q.options.length)].no] : [] }))
        await client.process('QB_ATTEMPT_SUBMIT', { attemptId: started.attemptId, publicationId: card.publicationId, answers })
        answered++
      } catch (e) {
        log(`  ${email}: ${card.name}: ${e.message}`)
      }
    }
    log(`taker     ${email}: answered ${answered}`)
  }
}

// ---- main --------------------------------------------------------------------------------------------------------

const USAGE = `usage: node ${basename(process.argv[1])} export|apply|renew|takers --base URL [--user EMAIL] [--pack DIR] [--dry-run]
  (password in QB_PASSWORD; see README.md)`

try {
  const options = args(process.argv.slice(2))
  if (options.command === 'export') await exportPack(options)
  else if (options.command === 'apply') await applyPack(options)
  else if (options.command === 'renew') await applyPack(options, { renew: true })
  else if (options.command === 'takers') await takers(options)
  else {
    console.error(USAGE)
    process.exit(2)
  }
} catch (e) {
  console.error(`error: ${e.message}`)
  process.exit(1)
}
