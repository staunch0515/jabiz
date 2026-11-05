import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import '../messages'
import type { MySponsorPage } from '../page/api'
import type { MyProduct } from '../products/api'
import type { Sponsor } from '../qb/api'
import { ProfileTransferCard } from './ProfileTransfer'
import { exportProfile, keepApplyDraft, packageFileName, takeApplyDraft, type ImportPlan } from './transfer'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const SPONSOR_ROW: Sponsor = {
  contactName: 'Sara', company: 'Acme Tea', intro: 'お茶の店', avatar: 'file-logo', reviewStatus: 'APPROVED',
  submittedAt: null, approvedAt: null, rejectReason: null, rejectedAt: null, revision: 2,
  introTranslations: JSON.stringify({ zh: { intro: { text: '茶叶店', hash: 'h' } } }),
}
const PAGE: MySponsorPage = {
  handle: 'acme-tea', language: 'ja', headline: 'おいしいお茶', cover: 'file-cover', website: 'https://acme.example',
  status: 'PUBLISHED', rejectReason: null, takedownReason: null, takenDownAt: null, publishedAt: null,
  reviewRequired: false, revision: 3, languages: ['ja', 'en'], translations: { en: { headline: 'Good tea' } },
  translationHashes: {},
}
const PRODUCT: MyProduct = {
  productId: 'pr1', code: 'TEA-1', name: '緑茶', description: null, image: 'file-logo', price: '1200', currency: 'JPY',
  shopUrl: 'https://acme.example/green', status: 'UNLISTED', seq: 1, takedownReason: null, revision: 1,
  translations: { en: { name: 'Green tea' } }, translationHashes: {},
}

describe('the profile package', () => {
  it('exports every part with each image once and the texts of every language', async () => {
    const read = vi.fn(async (id: string) => new Blob([id], { type: 'image/png' }))
    const document = await exportProfile({ sponsor: SPONSOR_ROW, page: PAGE, products: [PRODUCT],
      mailServer: { host: 'smtp.acme.example', port: 465, tls: 'SMTPS', username: 'shop',
        fromAddress: 'shop@acme.example', fromName: 'Acme', verifiedAt: null, testedAt: null, lastError: null },
      fallbackLanguage: 'en' }, new Date('2026-11-05T01:00:00Z'), read)
    expect(read).toHaveBeenCalledTimes(2)
    expect(document.languages).toEqual(['ja', 'zh', 'en'])
    expect(document.basics).toEqual({ contactName: 'Sara', company: 'Acme Tea', intro: { ja: 'お茶の店', zh: '茶叶店' },
      avatar: 'f1' })
    expect(document.page).toEqual({ handle: 'acme-tea', headline: { ja: 'おいしいお茶', en: 'Good tea' },
      website: 'https://acme.example', cover: 'f2' })
    expect(document.products[0]).toMatchObject({ productId: 'pr1', code: 'TEA-1', name: { ja: '緑茶', en: 'Green tea' },
      listed: false, image: 'f1' })
    expect(document.mailServer).toEqual({ host: 'smtp.acme.example', port: 465, tls: 'SMTPS', username: 'shop',
      fromAddress: 'shop@acme.example', fromName: 'Acme' })
    expect(JSON.stringify(document)).not.toContain('password')
    expect(document.files.f1).toMatchObject({ type: 'image/png', name: 'image.png', size: 9 })
    expect(packageFileName(document)).toBe('acme-tea-2026-11-05.quizbuks-profile.json')
  })

  it('keeps the basics for the application form once', () => {
    keepApplyDraft({ languages: ['ja', 'zh'], basics: { contactName: 'Sara', company: 'Acme',
      intro: { ja: 'お茶', zh: '茶' } } })
    expect(takeApplyDraft()).toEqual({ contactName: 'Sara', company: 'Acme', intro: 'お茶',
      introTranslations: { zh: { intro: '茶' } } })
    expect(takeApplyDraft()).toBeNull()
  })
})

const PLAN: ImportPlan = {
  basics: 'SAVE', page: 'PENDING', added: ['ほうじ茶'], changed: ['緑茶'], deleted: ['紅茶'], mailServer: 'PASSWORD',
  files: [{ path: 'basics.avatar', key: 'f1', policy: 'qb.sponsor-avatar', type: 'image/png', size: 3 }],
  links: [{ path: 'page.cover', url: 'https://img.example/cover.jpg' }],
  linked: [{ product: '紅茶', quizzes: ['Tea quiz'] }],
}

function server(importAnswer: { status: number; body: unknown } = { status: 200, body: { output: { plan: PLAN } } }) {
  const posted: { path: string; body: unknown }[] = []
  const uploads: string[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(input instanceof Request ? input.url : String(input), window.location.origin)
    const path = decodeURIComponent(url.pathname)
    if (path === '/api/files' && init?.method === 'POST') {
      uploads.push(url.searchParams.get('policy') ?? '')
      return json(201, { fileId: `up-${uploads.length}` })
    }
    const request = input instanceof Request ? input : new Request(url, init)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    posted.push({ path, body })
    if (path === '/api/auth/entries/sponsor') {
      return json(200, { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
        sessionPersistence: 'tab' })
    }
    if (path === '/api/auth/me') {
      return json(200, { userId: 'u2', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
        permissions: ['qb.sponsor.me'], emailVerified: true, idleTimeoutSeconds: 1800 })
    }
    if (path === '/api/processes/QB_PROFILE_IMPORT_CHECK/latest') return json(200, { output: { plan: PLAN } })
    if (path === '/api/processes/QB_PROFILE_IMPORT/latest') return json(importAnswer.status, importAnswer.body)
    return json(404, {})
  })
  return { posted, uploads }
}

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><ProfileTransferCard /></QueryClientProvider>)
}

const FILE = new File([JSON.stringify({ format: 'quizbuks-profile', schema: 1, languages: ['ja'],
  basics: { contactName: 'Sara', company: 'Acme', avatar: 'f1' }, products: [],
  files: { f1: { name: 'image.png', type: 'image/png', size: 3, data: 'AQID' } } })], 'acme.quizbuks-profile.json',
{ type: 'application/json' })

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

describe('importing a profile package', () => {
  it('shows the server\'s plan, asks for the mail password, uploads the images, then imports', async () => {
    const { posted, uploads } = server()
    renderCard()
    await userEvent.upload(screen.getByTestId('profile-import-file'), FILE)
    const dialog = await screen.findByTestId('profile-import-dialog')
    await within(dialog).findByTestId('profile-import-plan')
    expect(within(dialog).getByTestId('profile-import-page')).toHaveTextContent('not saved while it is in review')
    expect(within(dialog).getByTestId('profile-import-deleted')).toHaveTextContent('紅茶')
    expect(within(dialog).getByTestId('profile-import-links')).toHaveTextContent('https://img.example/cover.jpg')
    // The check never carries the images' bytes.
    const check = posted.find((p) => p.path.endsWith('QB_PROFILE_IMPORT_CHECK/latest'))!.body as
      { document: { files: Record<string, Record<string, unknown>> } }
    expect(check.document.files.f1.data).toBeUndefined()
    await expectAccessible()

    // D-Q100-13: a deleted product's quizzes are named, and the sponsor confirms they leave them.
    expect(within(dialog).getByTestId('profile-import-linked')).toHaveTextContent('紅茶 (shown on Tea quiz)')
    const confirm = within(dialog).getByTestId('profile-import-confirm')
    expect(confirm).toBeDisabled()
    await userEvent.type(within(dialog).getByTestId('profile-import-password'), 'secret')
    expect(confirm).toBeDisabled()
    await userEvent.click(within(dialog).getByTestId('profile-import-leaving'))
    await userEvent.click(confirm)
    await waitFor(() => expect(screen.queryByTestId('profile-import-dialog')).toBeNull())
    expect(uploads).toEqual(['qb.sponsor-avatar'])
    const imported = posted.find((p) => p.path.endsWith('QB_PROFILE_IMPORT/latest'))!.body as Record<string, unknown>
    expect(imported).toMatchObject({ files: { 'basics.avatar': 'up-1' }, mailPassword: 'secret' })
  })

  it('lists the server\'s reasons when the import is refused', async () => {
    server({ status: 422, body: { title: 'Refused', violations: [{ field: 'products[0].image',
      ruleCode: 'QB_FILE_NOT_OWN', message: 'Not your file' }] } })
    renderCard()
    await userEvent.upload(screen.getByTestId('profile-import-file'), FILE)
    const dialog = await screen.findByTestId('profile-import-dialog')
    await userEvent.type(await within(dialog).findByTestId('profile-import-password'), 'secret')
    await userEvent.click(within(dialog).getByTestId('profile-import-leaving'))
    await userEvent.click(within(dialog).getByTestId('profile-import-confirm'))
    expect(await within(dialog).findByTestId('profile-import-problems')).toHaveTextContent(
      'products[0].image: Not your file')
  })

  it('refuses a file that is not JSON before asking the server', async () => {
    const { posted } = server()
    renderCard()
    await userEvent.upload(screen.getByTestId('profile-import-file'), new File(['nope'], 'x.json',
      { type: 'application/json' }))
    expect(await screen.findByTestId('profile-import-problems')).toHaveTextContent('not a profile package')
    expect(posted.some((p) => p.path.includes('QB_PROFILE'))).toBe(false)
  })
})
