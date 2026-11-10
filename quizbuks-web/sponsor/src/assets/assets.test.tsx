import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import type { Asset } from './api'
import { LibraryActions } from './LibraryActions'
import { assetsMessages } from './messages'
import { formatBytes, sizeOf, usedPercent } from './usage'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const LOGO = { assetId: 'a1', sponsorId: 's1', kind: 'IMAGE', name: 'Shop logo', description: 'Square', language: 'en',
  tags: '["brand","logo"]', translations: null, image: 'f1', pdf: null, audio: null, url: null, durationSec: null,
  body: null, usage: null, prizeMethod: null, prizeDescription: null, digitalDelivery: null, digitalSendDays: null,
  revision: 3, editedAt: '2026-11-01T10:00:00Z' }
const INTRO = { ...LOGO, assetId: 'a2', kind: 'TEXT', name: 'Welcome text', description: null, tags: '["intro"]',
  image: null, body: 'Welcome to our quiz', usage: 'QUIZ_INTRO', revision: 1 }
const REDEEM = { ...INTRO, assetId: 'a3', name: 'Coffee at the counter', tags: null, body: 'Show this at the counter',
  usage: 'REDEEM_DESCRIPTION' }
const PRIZE = { ...LOGO, assetId: 'a4', kind: 'PRIZE', name: 'Gift card', tags: null, image: null,
  prizeMethod: 'DIGITAL_PRIZE', prizeDescription: 'A 500 yen card', digitalDelivery: 'POOL', revision: 2 }
const COVER = { ...LOGO, assetId: 'a5', name: 'Autumn cover', tags: '["season"]', image: 'f5', revision: 1 }

const KINDS = [['IMAGE', 'Image'], ['PDF', 'PDF'], ['AUDIO', 'Audio'], ['VIDEO_LINK', 'Video link'],
  ['LINK', 'Web link'], ['ARTICLE', 'Article'], ['TEXT', 'Text'], ['PRIZE', 'Prize card']]
const TARGETS = [['QUIZ_COVER', 'Quiz cover'], ['QUIZ_INTRO', 'Quiz introduction'], ['MATERIAL', 'Material'],
  ['PUBLICATION_COVER', 'Publication cover']]

/** The server as the asset pages see it, for an approved sponsor. Records what was posted. */
export function assetServer(options: { assets?: Record<string, unknown>[]; uses?: Record<string, unknown>[];
  candidates?: Record<string, unknown>[]; answers?: Record<string, (body: Record<string, unknown>) => Response>;
  usage?: Record<string, unknown>; stats?: Record<string, unknown>[]; shared?: Record<string, unknown>[];
  /** The library is read again only when this resolves, once the collection has been answered (a slow refresh). */
  holdLibraryAfterCollect?: Promise<unknown> } = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let collected = false
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    if (collected && path === '/api/datasets/urn:jabiz:dataset:me:QbAsset/query') await options.holdLibraryAfterCollect
    const name = /^\/api\/processes\/([A-Z_]+)\/latest$/.exec(path)?.[1]
    if (name && options.answers?.[name]) return options.answers[name](body)
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.sponsor.me'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/datasets/urn:jabiz:dataset:me:QbAsset/query':
        return json(200, { items: (body?.offset ? [] : options.assets ?? [LOGO, INTRO, PRIZE])
          .map((row) => ({ id: row.assetId, version: 1, attributes: row })), offset: 0, limit: 200 })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:asset-kind':
        return json(200, KINDS.map(([code, label]) => ({ code, label })))
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:asset-use-target':
        return json(200, TARGETS.map(([code, label]) => ({ code, label })))
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.asset-uses':
        return json(200, { items: options.uses ?? [], offset: 0, limit: 200 })
      // F-C2: no snapshot yet, and no sizes, unless the test gives them.
      case '/api/queries/qb.sponsor.usage':
        return json(200, { items: [options.usage ?? { day: null, storageBytes: null, storageFiles: null,
          assetsCount: null, freeBytes: 524288000, overFree: false }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.asset-stats':
        return json(200, { items: body?.offset ? [] : options.stats ?? [], offset: 0, limit: 500 })
      // F-C3: the Sponsor's shared videos (none unless the test gives them).
      case '/api/queries/qb.sponsor.my-shared-videos':
        return json(200, { items: options.shared ?? [], offset: 0, limit: 200 })
      case '/api/queries/qb.sponsor.asset-candidates':
        return json(200, { items: options.candidates ?? [], offset: 0, limit: 200 })
      case '/api/processes/QB_ASSET_SAVE/latest':
        return json(200, { output: { assetId: body?.assetId ?? 'new1', revision: 1 } })
      case '/api/processes/QB_ASSET_DELETE/latest':
        return json(200, { output: { assetId: body?.assetId, uses: 0 } })
      case '/api/processes/QB_ASSET_COLLECT/latest':
        collected = true
        return json(200, { output: { created: [{ index: 0, assetId: 'c1' }], skipped: [{ index: 1, assetId: 'a1',
          sameAs: null }] } })
      default:
        return json(404, {})
    }
  })
  const calls = (process: string) => posted.filter((p) => p.path === `/api/processes/${process}/latest`)
    .map((p) => p.body)
  return { calls, posted }
}

function openLibrary(search = '') {
  window.history.replaceState(null, '', `/assets${search}`)
  render(<App />)
}

const shownNames = () => screen.getAllByTestId('asset-row').map((row) => within(row).getByTestId('asset-name-cell')
  .textContent)

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('the asset library (F-C1)', () => {
  it('has every text in every language', () => {
    const english = keyPaths(assetsMessages.en)
    for (const lng of ['zh', 'ja'] as const) expect(keyPaths(assetsMessages[lng]), lng).toEqual(english)
    for (const lng of LANGUAGES) {
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
    // The naming of F-F5: the Sponsor's pages are never called the back office.
    for (const lng of LANGUAGES) expect(JSON.stringify(assetsMessages[lng])).not.toMatch(/后台|back office|管理画面/)
  })

  it('is in the content menu and lists the assets in tabs, searched and filtered by tag', async () => {
    assetServer({ assets: [LOGO, INTRO, REDEEM, PRIZE] })
    openLibrary()
    expect(await screen.findByRole('link', { name: 'Asset library' })).toHaveAttribute('aria-current', 'page')
    await waitFor(() => expect(screen.getAllByTestId('asset-row')).toHaveLength(4))
    expect(screen.getByTestId('assets-count')).toHaveTextContent('4 assets')
    // The texts tab.
    await userEvent.click(screen.getByTestId('assets-tab-text'))
    await waitFor(() => expect(shownNames()).toEqual(['Welcome text', 'Coffee at the counter']))
    expect(window.location.search).toBe('?tab=text')
    // The prizes tab.
    await userEvent.click(screen.getByTestId('assets-tab-prize'))
    await waitFor(() => expect(shownNames()).toEqual(['Gift card']))
    // All again: a tag found by the search, then the tag filter.
    await userEvent.click(screen.getByTestId('assets-tab-all'))
    await userEvent.type(screen.getByTestId('assets-search'), 'LOGO')
    await waitFor(() => expect(shownNames()).toEqual(['Shop logo']))
    await userEvent.clear(screen.getByTestId('assets-search'))
    await userEvent.click(screen.getByTestId('assets-tag'))
    await userEvent.click(await screen.findByRole('option', { name: 'intro' }))
    await waitFor(() => expect(shownNames()).toEqual(['Welcome text']))
    await userEvent.type(screen.getByTestId('assets-search'), 'nothing like it')
    expect(await screen.findByTestId('assets-none')).toBeInTheDocument()
    await expectAccessible()
  })

  it('opens on the tab of its address and says when the library is empty', async () => {
    assetServer({ assets: [] })
    openLibrary('?tab=prize')
    expect(await screen.findByTestId('assets-empty')).toBeInTheDocument()
    expect(screen.getByTestId('assets-tab-prize')).toHaveAttribute('data-state', 'active')
    await expectAccessible()
  })

  it('deletes an asset after confirming, with the revision read', async () => {
    const { calls } = assetServer()
    openLibrary()
    await userEvent.click(await screen.findByRole('button', { name: 'Delete Shop logo' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(calls('QB_ASSET_DELETE')).toEqual([{ assetId: 'a1', baseRevision: 3 }]))
  })

  it('shows where an asset is used, marking the places with older content', async () => {
    const { posted } = assetServer({ uses: [
      { useId: 'u1', assetId: 'a1', targetKind: 'QUIZ_COVER', targetId: 'q1', quizId: 'q1', publicationId: null,
        targetName: 'Coffee quiz', stale: true, usedAt: '2026-11-01T00:00:00Z' },
      { useId: 'u2', assetId: 'a1', targetKind: 'PUBLICATION_COVER', targetId: 'p1', quizId: 'q1',
        publicationId: 'p1', targetName: 'Autumn round', stale: false, usedAt: '2026-11-02T00:00:00Z' },
    ] })
    openLibrary()
    await userEvent.click(await screen.findByRole('button', { name: 'Where Shop logo is used' }))
    const dialog = await screen.findByTestId('asset-uses-dialog')
    const uses = await within(dialog).findAllByTestId('asset-use')
    expect(uses.map((use) => use.dataset.stale)).toEqual(['true', 'false'])
    expect(within(uses[0]).getByTestId('asset-use-stale')).toHaveTextContent('Uses older content')
    expect(within(uses[0]).getByRole('link', { name: 'Coffee quiz' })).toHaveAttribute('href', '/quizzes/q1')
    expect(within(uses[1]).getByRole('link', { name: 'Autumn round' })).toHaveAttribute('href', '/publications/p1')
    expect(posted.find((p) => p.path === '/api/queries/qb.sponsor.asset-uses')?.body.filters)
      .toEqual([{ field: 'assetId', op: 'eq', value: 'a1' }])
    await expectAccessible()
  })

  it('makes a text, sending only its own content and its tags', async () => {
    const { calls } = assetServer()
    openLibrary()
    await userEvent.click(await screen.findByTestId('asset-add'))
    const dialog = await screen.findByTestId('asset-dialog')
    await userEvent.click(within(dialog).getByTestId('asset-kind-TEXT'))
    await userEvent.type(within(dialog).getByTestId('asset-name'), 'Pickup rules')
    await userEvent.click(within(dialog).getByTestId('asset-usage'))
    await userEvent.click(await screen.findByRole('option', { name: 'REDEEM_DESCRIPTION' }))
    await userEvent.type(within(dialog).getByTestId('asset-body'), 'Bring your phone')
    await userEvent.type(within(dialog).getByTestId('asset-tag-input'), 'store{Enter}')
    await userEvent.type(within(dialog).getByTestId('asset-tag-input'), 'rules')
    await userEvent.click(within(dialog).getByTestId('asset-tag-add'))
    expect(within(dialog).getAllByTestId('asset-tag').map((tag) => tag.textContent)).toEqual(['store', 'rules'])
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove the tag store' }))
    await expectAccessible()
    await userEvent.click(within(dialog).getByTestId('asset-save'))
    await waitFor(() => expect(calls('QB_ASSET_SAVE')).toHaveLength(1))
    const sent = calls('QB_ASSET_SAVE')[0]
    expect(sent).toMatchObject({ assetId: null, kind: 'TEXT', name: 'Pickup rules', description: null,
      tags: ['rules'], body: 'Bring your phone', usage: 'REDEEM_DESCRIPTION', baseRevision: null })
    // Another kind's fields are not sent.
    expect(Object.keys(sent)).not.toContain('image')
    expect(Object.keys(sent)).not.toContain('prizeMethod')
  })

  it('edits with the revision read and shows every problem of the server at once, at its field', async () => {
    const { calls } = assetServer({ answers: { QB_ASSET_SAVE: () => json(422, { title: 'Unprocessable', status: 422,
      violations: [
        { field: 'name', ruleCode: 'QB_ASSET_NAME_BLANK', message: 'Give the asset a name.' },
        { field: 'tags[1]', ruleCode: 'QB_ASSET_TAG_TOO_LONG', message: 'A tag has at most 30 characters.' },
        { field: 'assetId', ruleCode: 'QB_ASSET_LIMIT', message: 'The library is full.' },
      ] }) } })
    openLibrary()
    await userEvent.click(await screen.findByRole('button', { name: 'Edit Shop logo' }))
    const dialog = await screen.findByTestId('asset-dialog')
    expect(within(dialog).getByTestId('asset-kind-value')).toHaveTextContent('Image')
    expect(within(dialog).getByTestId('asset-image-current')).toBeInTheDocument()
    await userEvent.clear(within(dialog).getByTestId('asset-name'))
    await userEvent.click(within(dialog).getByTestId('asset-save'))
    expect(await within(dialog).findByText('Give the asset a name.')).toBeInTheDocument()
    expect(within(dialog).getByText('A tag has at most 30 characters.')).toBeInTheDocument()
    expect(within(dialog).getByTestId('asset-error')).toHaveTextContent('The library is full.')
    expect(within(dialog).getByTestId('asset-name')).toHaveAttribute('aria-invalid', 'true')
    expect(calls('QB_ASSET_SAVE')[0]).toMatchObject({ assetId: 'a1', kind: 'IMAGE', image: 'f1', baseRevision: 3,
      tags: ['brand', 'logo'], language: 'en' })
    await expectAccessible()
  })

  it('collects the candidates ticked and tells what was added and skipped', async () => {
    const { calls } = assetServer({ candidates: [
      { candidateKey: 'f:f9', kind: 'IMAGE', usage: null, sourceKind: 'QUIZ_COVER', sourceId: 'q1', quizId: 'q1',
        publicationId: null, name: 'Coffee quiz', description: null, language: 'en', image: 'f9' },
      { candidateKey: 't:TEXT:QUIZ_INTRO:h', kind: 'TEXT', usage: 'QUIZ_INTRO', sourceKind: 'QUIZ_INTRO',
        sourceId: 'q1', quizId: 'q1', publicationId: null, name: null, description: null, language: 'ja',
        body: 'ようこそ' },
    ] })
    openLibrary()
    await userEvent.click(await screen.findByTestId('asset-collect'))
    const dialog = await screen.findByTestId('asset-collect-dialog')
    expect(await within(dialog).findAllByTestId('asset-candidate')).toHaveLength(2)
    expect(within(dialog).getByTestId('asset-collect-run')).toBeDisabled()
    await userEvent.click(within(dialog).getByTestId('asset-collect-all'))
    expect(within(dialog).getByText('2 selected')).toBeInTheDocument()
    await expectAccessible()
    await userEvent.click(within(dialog).getByTestId('asset-collect-run'))
    expect(await within(dialog).findByTestId('asset-collect-done'))
      .toHaveTextContent('1 added to the library; 1 skipped as already there.')
    expect(calls('QB_ASSET_COLLECT')).toEqual([{ items: [
      { kind: 'IMAGE', name: 'Coffee quiz', description: null, language: 'en', tags: [], image: 'f9' },
      // Without a name of its own, the candidate is named by its place.
      { kind: 'TEXT', name: 'Quiz introduction', description: null, language: 'ja', tags: [], body: 'ようこそ',
        usage: 'QUIZ_INTRO' },
    ] }])
  })
})

describe('the collection dialog while the library is read again', () => {
  it('closes as soon as it says what was added, however slowly the library is read again', async () => {
    let release!: () => void
    const held = new Promise<void>((resolve) => { release = resolve })
    assetServer({ holdLibraryAfterCollect: held, candidates: [
      { candidateKey: 'f:f9', kind: 'IMAGE', usage: null, sourceKind: 'QUIZ_COVER', sourceId: 'q1', quizId: 'q1',
        publicationId: null, name: 'Coffee quiz', description: null, language: 'en', image: 'f9' },
    ] })
    openLibrary()
    await userEvent.click(await screen.findByTestId('asset-collect'))
    const dialog = await screen.findByTestId('asset-collect-dialog')
    await userEvent.click(await within(dialog).findByTestId('asset-collect-all'))
    await userEvent.click(within(dialog).getByTestId('asset-collect-run'))
    expect(await within(dialog).findByTestId('asset-collect-done')).toBeInTheDocument()
    // The library is still being read: a sponsor who is done presses Escape, and the dialog goes.
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByTestId('asset-collect-dialog')).toBeNull())
    release()
  })
})

describe('choosing from the library and saving to it (F-C1)', () => {
  function renderActions(props: Partial<Parameters<typeof LibraryActions>[0]> = {}) {
    const onPick = vi.fn<(asset: Asset) => void>()
    const onStored = vi.fn()
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <LibraryActions target="QUIZ_COVER" onPick={onPick} onStored={onStored} testId="cover-library" {...props} />
      </QueryClientProvider>,
    )
    return { onPick, onStored }
  }

  it('lists only the assets that suit the place and chooses one with the keyboard', async () => {
    assetServer({ assets: [LOGO, INTRO, PRIZE, COVER] })
    const { onPick } = renderActions()
    await userEvent.click(screen.getByTestId('cover-library-pick'))
    const picker = await screen.findByTestId('asset-picker')
    const items = await within(picker).findAllByTestId('asset-picker-item')
    expect(items.map((item) => item.dataset.id)).toEqual(['a1', 'a5'])
    await expectAccessible()
    // Typing searches (names and tags); the arrow keys move, Enter chooses.
    await userEvent.type(within(picker).getByTestId('asset-picker-search'), 'season')
    await waitFor(() => expect(within(picker).getAllByTestId('asset-picker-item').map((i) => i.dataset.id))
      .toEqual(['a5']))
    await userEvent.clear(within(picker).getByTestId('asset-picker-search'))
    await userEvent.keyboard('{ArrowDown}{Enter}')
    await waitFor(() => expect(onPick).toHaveBeenCalledTimes(1))
    expect(onPick.mock.calls[0][0]).toMatchObject({ assetId: 'a5', image: 'f5' })
    await waitFor(() => expect(screen.queryByTestId('asset-picker')).not.toBeInTheDocument())
  })

  it('lists a prize card of a method not allowed without letting it be chosen', async () => {
    assetServer({ assets: [PRIZE] })
    const { onPick } = renderActions({ target: 'PUBLICATION_PRIZE', refused: () => 'Not allowed here' })
    await userEvent.click(screen.getByTestId('cover-library-pick'))
    const item = await screen.findByTestId('asset-picker-item')
    expect(item).toHaveAttribute('aria-disabled', 'true')
    expect(item).toHaveTextContent('Not allowed here')
    await userEvent.click(item)
    expect(onPick).not.toHaveBeenCalled()
  })

  it('says when nothing of the library fits', async () => {
    assetServer({ assets: [INTRO] })
    renderActions()
    await userEvent.click(screen.getByTestId('cover-library-pick'))
    expect(await screen.findByTestId('asset-picker-none')).toBeInTheDocument()
  })

  it('saves what the place has to the library under the name given', async () => {
    const { calls } = assetServer()
    const { onStored } = renderActions({ store: { kind: 'IMAGE', name: 'Coffee quiz', language: 'ja',
      content: { image: 'f7' } } })
    await userEvent.click(screen.getByTestId('cover-library-store'))
    const dialog = await screen.findByTestId('asset-store-dialog')
    await userEvent.clear(within(dialog).getByTestId('asset-store-name'))
    await userEvent.type(within(dialog).getByTestId('asset-store-name'), 'Coffee cover')
    await expectAccessible()
    await userEvent.click(within(dialog).getByTestId('asset-store-save'))
    await waitFor(() => expect(onStored).toHaveBeenCalledWith('new1', expect.objectContaining({ kind: 'IMAGE' })))
    expect(calls('QB_ASSET_SAVE')).toEqual([{ assetId: null, kind: 'IMAGE', name: 'Coffee cover', description: null,
      language: 'ja', tags: [], confirmed: [], baseRevision: null, image: 'f7' }])
  })

  it('offers no saving while the place is empty', async () => {
    assetServer()
    renderActions({ store: null })
    expect(screen.getByTestId('cover-library-store')).toBeDisabled()
  })
})

const USAGE_LOGO = { assetId: 'a1', sponsorId: 's1', kind: 'IMAGE', name: 'Shop logo', description: null, language: 'en',
  tags: null, translations: null, image: 'f1', pdf: null, audio: null, url: null, durationSec: null, body: null,
  usage: null, prizeMethod: null, prizeDescription: null, digitalDelivery: null, digitalSendDays: null, revision: 1,
  editedAt: '2026-11-01T10:00:00Z' }
const USAGE_VIDEO = { ...USAGE_LOGO, assetId: 'a2', kind: 'VIDEO_LINK', name: 'Shop tour', image: null,
  url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', durationSec: 90 }
const USAGE_POSTER = { ...USAGE_LOGO, assetId: 'a3', name: 'Poster', image: 'f3' }

const MB = 1024 * 1024
const USAGE_STATS = [
  { assetId: 'a1', fileBytes: 2 * MB, textBytes: 9, uses: 2 },
  { assetId: 'a2', fileBytes: 0, textBytes: 9, uses: 0 },
  { assetId: 'a3', fileBytes: 30 * MB, textBytes: 6, uses: 0 },
]

describe('the usage on the asset library (F-C2)', () => {
  it('formats bytes and the share of the free amount', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(300)).toBe('300 B')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(500 * MB)).toBe('500.0 MB')
    expect(formatBytes(3 * 1024 * MB)).toBe('3.0 GB')
    expect(formatBytes(null)).toBe('0 B')
    expect(usedPercent({ storageBytes: 250 * MB, freeBytes: 500 * MB })).toBe(50)
    expect(usedPercent({ storageBytes: 900 * MB, freeBytes: 500 * MB })).toBe(100)
    expect(usedPercent({ storageBytes: 1, freeBytes: null })).toBeNull()
    expect(sizeOf({ fileBytes: 10, textBytes: 5, uses: 0 })).toBe(15)
    expect(sizeOf(undefined)).toBe(0)
  })

  it('shows used against free, the files and the assets as of the latest snapshot, without variants', async () => {
    assetServer({ assets: [USAGE_LOGO, USAGE_VIDEO], usage: { day: '2026-11-06', storageBytes: 120 * MB, storageFiles: 12,
      assetsCount: 34, freeBytes: 500 * MB, overFree: false } })
    openLibrary()
    expect(await screen.findByTestId('usage-used')).toHaveTextContent('Used 120.0 MB of 500.0 MB free')
    expect(screen.getByTestId('usage-files')).toHaveTextContent('Files: 12')
    expect(screen.getByTestId('usage-assets')).toHaveTextContent('Assets: 34')
    expect(screen.getByTestId('usage-meter')).toHaveAttribute('aria-valuenow', '24')
    const card = screen.getByTestId('usage-card')
    expect(card).toHaveTextContent('without thumbnails or other variants')
    expect(card).toHaveTextContent('Nothing is charged for storage in the first launch.')
    expect(screen.queryByTestId('usage-over')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getAllByTestId('asset-row')).toHaveLength(2))
    await expectAccessible()
  })

  it('says when there is no snapshot yet', async () => {
    assetServer({ assets: [] })
    openLibrary()
    expect(await screen.findByTestId('usage-card')).toHaveTextContent('Your usage shows here from tomorrow')
    expect(screen.queryByTestId('usage-used')).not.toBeInTheDocument()
    await expectAccessible()
  })

  it('above the free amount shows a banner that leads to the unused assets', async () => {
    assetServer({ assets: [USAGE_LOGO, USAGE_VIDEO, USAGE_POSTER], stats: USAGE_STATS, usage: { day: '2026-11-06', storageBytes: 600 * MB,
      storageFiles: 40, assetsCount: 3, freeBytes: 500 * MB, overFree: true } })
    openLibrary('?tab=products')
    const banner = await screen.findByTestId('usage-over')
    expect(banner).toHaveTextContent('You are above the free storage')
    expect(banner).toHaveTextContent('Your files use 600.0 MB, above the free 500.0 MB. Uploads keep working')
    await expectAccessible()
    await userEvent.click(within(banner).getByTestId('usage-show-unused'))
    await waitFor(() => expect(window.location.search).toBe('?tab=all'))
    await waitFor(() => expect(shownNames()).toEqual(['Shop tour', 'Poster']))
    expect(screen.getByTestId('assets-unused')).toHaveAttribute('data-state', 'checked')
  })

  it('sorts the library by size and filters the unused assets', async () => {
    assetServer({ assets: [USAGE_LOGO, USAGE_VIDEO, USAGE_POSTER], stats: USAGE_STATS })
    openLibrary()
    await waitFor(() => expect(screen.getAllByTestId('asset-size')).toHaveLength(3))
    expect(screen.getAllByTestId('asset-size').map((cell) => cell.textContent)).toEqual(['2.0 MB', '9 B', '30.0 MB'])
    // Largest first, then smallest first.
    await userEvent.click(screen.getByRole('button', { name: /Size/ }))
    await waitFor(() => expect(shownNames()).toEqual(['Poster', 'Shop logo', 'Shop tour']))
    await userEvent.click(screen.getByRole('button', { name: /Size/ }))
    await waitFor(() => expect(shownNames()).toEqual(['Shop tour', 'Shop logo', 'Poster']))
    // Only those copied nowhere.
    await userEvent.click(screen.getByTestId('assets-unused'))
    await waitFor(() => expect(shownNames()).toEqual(['Shop tour', 'Poster']))
    await expectAccessible()
  })
})

/** A video share of the Sponsor's, as `qb.sponsor.my-shared-videos` gives it. */
const shareOf = (assetId: string, status: string, sharedAt: string, change: Record<string, unknown> = {}) => ({
  sharedVideoId: `${assetId}-${sharedAt}`, assetId, title: JSON.stringify({ zh: '视频', ja: '動画', en: 'Video' }),
  url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', durationSec: 90, vertical: false, topics: 'pets', status,
  sharedAt, approvedAt: null, rejectReason: null, withdrawnAt: null, takenDownAt: null, takedownReason: null,
  revision: 1, usesRunning: 0, ...change })

describe('the sharing on the asset library (F-C3)', () => {
  const video = (assetId: string, name: string) => ({ ...USAGE_VIDEO, assetId, name })

  it('shows how each video link\'s latest share stands, and offers sharing only while none stands', async () => {
    const { calls } = assetServer({
      assets: [USAGE_LOGO, video('v-none', 'Never shared'), video('v-review', 'In review'),
        video('v-shared', 'Shared'), video('v-back', 'Sent back'), video('v-gone', 'Taken down'),
        video('v-again', 'Shared again')],
      shared: [
        shareOf('v-review', 'PENDING', '2026-11-08T03:00:00Z'),
        shareOf('v-shared', 'APPROVED', '2026-11-08T02:00:00Z'),
        shareOf('v-back', 'REJECTED', '2026-11-08T01:00:00Z', { rejectReason: 'Not entertainment' }),
        shareOf('v-gone', 'TAKEN_DOWN', '2026-11-08T01:00:00Z', { takedownReason: 'Infringes' }),
        // The latest share counts: withdrawn, then shared again and in review.
        shareOf('v-again', 'PENDING', '2026-11-08T05:00:00Z'),
        shareOf('v-again', 'WITHDRAWN', '2026-11-08T04:00:00Z'),
      ],
    })
    openLibrary()
    await waitFor(() => expect(screen.getAllByTestId('asset-row')).toHaveLength(7))
    const row = (id: string) => screen.getAllByTestId('asset-row').find((each) => each.getAttribute('data-id') === id)!
    await waitFor(() => expect(within(row('v-review')).getByTestId('shared-video-status'))
      .toHaveAttribute('data-status', 'PENDING'))
    const states = Object.fromEntries(['v-none', 'v-review', 'v-shared', 'v-back', 'v-gone', 'v-again'].map((id) => [id,
      within(row(id)).getByTestId('shared-video-status').textContent]))
    expect(states).toEqual({ 'v-none': 'Not shared', 'v-review': 'In review', 'v-shared': 'Shared',
      'v-back': 'Sent back', 'v-gone': 'Taken down', 'v-again': 'In review' })
    // Not a video link: no sharing at all.
    expect(within(row('a1')).queryByTestId('shared-video-status')).toBeNull()
    expect(within(row('a1')).queryByTestId('asset-share')).toBeNull()
    // Shared or in review: no second share; never shared, sent back or taken down: shared (again).
    for (const id of ['v-review', 'v-shared', 'v-again']) expect(within(row(id)).queryByTestId('asset-share'), id).toBeNull()
    for (const id of ['v-none', 'v-back', 'v-gone']) expect(within(row(id)).getByTestId('asset-share'), id).toBeInTheDocument()
    await expectAccessible()
    // Sharing a sent-back one again opens the dialog for it.
    await userEvent.click(within(row('v-back')).getByTestId('asset-share'))
    expect(await screen.findByTestId('share-video-dialog')).toBeInTheDocument()
    expect(calls('QB_SHARED_VIDEO_SHARE')).toEqual([])
  })
})
