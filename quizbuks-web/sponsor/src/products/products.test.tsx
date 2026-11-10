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
import { formatPrice, priceScale } from '../qb/products'
import { productsEn, productsJa, productsZh } from './messages'
import { QuizProductsPanel } from './QuizProductsPanel'
import { readMyProducts } from './api'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const RYE = { productId: 'p1', userId: 's1', name: 'Rye loaf', description: 'Baked daily', image: null, price: '1200',
  currency: 'JPY', shopUrl: 'https://shop.example/rye', status: 'LISTED', seq: 0, takedownReason: null, revision: 2 }
const CAKE = { ...RYE, productId: 'p2', name: 'Cake', price: '9.50', currency: 'USD', shopUrl: 'https://shop.example/cake',
  status: 'TAKEN_DOWN', seq: 1, takedownReason: 'Misleading', revision: 4 }

/** The server as the products pages see it, for an approved sponsor. Records what was posted. */
function server(options: { products?: Record<string, unknown>[]; links?: Record<string, unknown>[];
  answers?: Record<string, () => Response>; limit?: number | null | 'fail'; paged?: boolean
  /** F-S6d: the quiz's questions as `qb.sponsor.quiz-questions` numbers them. */
  questions?: Record<string, unknown>[] } = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    const name = /^\/api\/processes\/([A-Z_]+)\/latest$/.exec(path)?.[1]
    if (name && options.answers?.[name]) return options.answers[name]()
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
      case '/api/datasets/urn:jabiz:dataset:me:QbSponsorProduct/query': {
        const all = (options.products ?? [RYE, CAKE]).map((row) => ({ id: row.productId, version: 1, attributes: row }))
        if (!options.paged) return json(200, { items: all, offset: 0, limit: 200 })
        // As the server answers: a page from the offset, and the total.
        const offset = Number(body?.offset ?? 0)
        const limit = Number(body?.limit ?? 20)
        return json(200, { items: all.slice(offset, offset + limit), total: all.length, offset, limit })
      }
      // F-C11: the products' limit (qb.products.max).
      case '/api/queries/qb.sponsor.product-limit':
        if (options.limit === 'fail') return json(404, {})
        return json(200, { items: [{ max: options.limit === undefined ? 50 : options.limit }], offset: 0, limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:me:QbQuizProduct/query':
        return json(200, { items: (options.links ?? []).map((row, i) => ({ id: `l${i}`, version: 1, attributes: row })),
          offset: 0, limit: 200 })
      case '/api/queries/qb.sponsor.quiz-questions': {
        const all = options.questions ?? []
        const offset = Number(body?.offset ?? 0)
        const limit = Number(body?.limit ?? 50)
        return json(200, { items: all.slice(offset, offset + limit), offset, limit, total: all.length })
      }
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/processes/QB_PRODUCT_SAVE/latest':
        return json(200, { output: { productId: body?.productId ?? 'new1', status: 'LISTED', revision: 1 } })
      case '/api/processes/QB_PRODUCT_DELETE/latest':
        return json(200, { output: { productId: body?.productId, status: null, revision: 1 } })
      case '/api/processes/QB_QUIZ_PRODUCTS_SET/latest':
        return json(200, { output: { quizId: body?.quizId, links: 1 } })
      default:
        return json(404, {})
    }
  })
  const calls = (process: string) => posted.filter((p) => p.path === `/api/processes/${process}/latest`)
    .map((p) => p.body)
  return { calls, posted }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe("the Sponsor's products (Q60b)", () => {
  it('have every text in every language, and format prices with their currency’s decimals', () => {
    const english = keyPaths(productsEn)
    for (const [lng, texts] of [['zh', productsZh], ['ja', productsJa]] as const) {
      expect(keyPaths(texts), lng).toEqual(english)
    }
    for (const lng of LANGUAGES) {
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
    expect(priceScale('JPY')).toBe(0)
    expect(priceScale('USD')).toBe(2)
    expect(formatPrice('1200', 'JPY', 'en')).toMatch(/1,200/)
    expect(formatPrice('9.5', 'USD', 'en')).toBe('$9.50')
  })

  it('lists them with their state and the reason of a takedown', async () => {
    server()
    window.history.replaceState(null, '', '/assets/products')
    render(<App />)
    const rows = await screen.findAllByTestId('product')
    expect(rows.map((row) => row.dataset.status)).toEqual(['LISTED', 'TAKEN_DOWN'])
    expect(within(rows[1]).getByTestId('product-takedown')).toHaveTextContent('Misleading')
    await expectAccessible()
  })

  it('adds one, last, with its price as typed', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', '/assets/products')
    render(<App />)
    await userEvent.click(await screen.findByTestId('product-add'))
    const dialog = await screen.findByTestId('product-dialog')
    await userEvent.type(within(dialog).getByTestId('product-name'), 'Baguette')
    await userEvent.type(within(dialog).getByTestId('product-price'), '350')
    await userEvent.type(within(dialog).getByTestId('product-shop'), 'https://shop.example/baguette')
    // Q100e: the sponsor's own code, trimmed.
    await userEvent.type(within(dialog).getByTestId('product-code'), ' BREAD-1 ')
    await userEvent.click(within(dialog).getByTestId('product-save'))
    await waitFor(() => expect(calls('QB_PRODUCT_SAVE')).toHaveLength(1))
    expect(calls('QB_PRODUCT_SAVE')[0]).toMatchObject({ productId: null, name: 'Baguette', price: '350',
      currency: 'JPY', shopUrl: 'https://shop.example/baguette', listed: true, seq: 2, baseRevision: null,
      code: 'BREAD-1' })
  })

  // F-C1c: the products are the asset library's tab; the hub's address and the one before it lead there.
  it.each(['/profile/products', '/products'])('leads %s to the asset library’s products tab', async (from) => {
    server()
    window.history.replaceState(null, '', from)
    render(<App />)
    await waitFor(() => expect(window.location.pathname + window.location.search).toBe('/assets/products'))
    expect(await screen.findByTestId('products')).toBeInTheDocument()
    expect(screen.getByTestId('page-tab-products')).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Asset library' })).toHaveAttribute('aria-current', 'page')
    // The hub no longer has a section of its own for them.
    expect(screen.queryByTestId('page-tab-products-section')).toBeNull()
  })

  it("shows the server's refusal of a price at the field", async () => {
    server({ answers: { QB_PRODUCT_SAVE: () => json(422, { title: 'Unprocessable', status: 422, violations: [
      { field: 'price', ruleCode: 'QB_PRODUCT_PRICE', message: 'No decimals in yen.' }] }) } })
    window.history.replaceState(null, '', '/assets/products')
    render(<App />)
    await userEvent.click((await screen.findAllByTestId('product-edit'))[0])
    const dialog = await screen.findByTestId('product-dialog')
    await userEvent.click(within(dialog).getByTestId('product-save'))
    expect(await within(dialog).findByText('No decimals in yen.')).toBeInTheDocument()
    expect(within(dialog).getByTestId('product-price')).toHaveAttribute('aria-invalid', 'true')
  })

  // F-C11: the limit is the parameter qb.products.max, as the server reads it.
  it('take the limit from the server', async () => {
    const { calls } = server({ limit: 2 })
    window.history.replaceState(null, '', '/assets/products')
    render(<App />)
    expect(await screen.findByTestId('products-full')).toHaveTextContent('You have 2 products')
    expect(screen.getByTestId('product-add')).toBeDisabled()
    expect(screen.getByTestId('products-intro')).toHaveTextContent('Up to 2 products.')
    await expectAccessible()
    expect(calls('QB_PRODUCT_SAVE')).toEqual([])
  })

  it('with a higher limit, add more; unknown, leave it to the save', async () => {
    server({ limit: 3 })
    window.history.replaceState(null, '', '/assets/products')
    const { unmount } = render(<App />)
    await waitFor(() => expect(screen.getByTestId('products-intro')).toHaveTextContent('Up to 3 products.'))
    expect(await screen.findByTestId('product-add')).toBeEnabled()
    expect(screen.queryByTestId('products-full')).toBeNull()
    unmount()
    vi.restoreAllMocks()

    server({ limit: 'fail' })
    render(<App />)
    expect(await screen.findAllByTestId('product')).toHaveLength(2)
    await waitFor(() => expect(screen.getByTestId('products-intro')).not.toHaveTextContent(/\d/))
    expect(screen.getByTestId('product-add')).toBeEnabled()
    expect(screen.queryByTestId('products-full')).toBeNull()
  })

  it('are read a page at a time, all of them', async () => {
    const many = Array.from({ length: 450 }, (_, i) => ({ ...RYE, productId: `p${i}`, name: `Item ${i}`, seq: i }))
    server({ products: many, paged: true })
    const products = await readMyProducts()
    expect(products).toHaveLength(450)
    expect(products.at(-1)?.productId).toBe('p449')
  })

  it('moves one by swapping places with its neighbour, and deletes one on confirmation', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', '/assets/products')
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'Move Rye loaf down' }))
    await waitFor(() => expect(calls('QB_PRODUCT_SAVE')).toHaveLength(2))
    expect(calls('QB_PRODUCT_SAVE').map((c) => [c.productId, c.seq])).toEqual([['p2', 0], ['p1', 1]])
    await userEvent.click(screen.getAllByTestId('product-delete')[0])
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(calls('QB_PRODUCT_DELETE')).toEqual([{ productId: 'p1' }]))
  })
})

describe("a quiz's products (Q60b)", () => {
  const questions = [
    { questionId: 'q1', questionNo: 1, stem: 'Capital of France?', image: null, points: 2, optionCount: 0 },
    { questionId: 'q2', questionNo: 2, stem: 'Capital of Japan?', image: null, points: 3, optionCount: 0 },
  ]

  function renderPanel() {
    return render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <QuizProductsPanel quizId="quiz1" />
      </QueryClientProvider>,
    )
  }

  it('starts from the links saved and sends the whole quiz’s again', async () => {
    const { calls } = server({ links: [{ quizId: 'quiz1', questionNo: 0, productId: 'p2', seq: 0 },
      { quizId: 'quiz1', questionNo: 2, productId: 'p1', seq: 0 },
      { quizId: 'quiz1', questionNo: 3, productId: 'p1', seq: 0 }], questions })
    renderPanel()
    expect(await screen.findByTestId('quiz-product-slot-1')).toHaveTextContent('Cake')
    expect(screen.getByTestId('quiz-product-slot-2')).toHaveTextContent('None')
    expect(await screen.findByTestId('question-product-2')).toHaveTextContent('Rye loaf')
    // A number beyond the draft's questions is shown, so it can be removed.
    expect(screen.getByTestId('question-product-3-gone')).toBeInTheDocument()
    await userEvent.click(screen.getByTestId('question-product-3'))
    await userEvent.click(await screen.findByRole('option', { name: 'None' }))
    await userEvent.click(screen.getByTestId('quiz-product-slot-2'))
    await userEvent.click(await screen.findByRole('option', { name: 'Rye loaf' }))
    await userEvent.click(screen.getByTestId('quiz-products-save'))
    await waitFor(() => expect(calls('QB_QUIZ_PRODUCTS_SET')).toEqual([{ quizId: 'quiz1',
      quizProducts: ['p2', 'p1'], questionProducts: [{ questionNo: 2, productId: 'p1' }] }]))
    expect(await screen.findByTestId('quiz-products-done')).toBeInTheDocument()
    await expectAccessible()
  })

  it('lists a large quiz\'s questions a page at a time and keeps the choices of every page (F-S6d)', async () => {
    const many = Array.from({ length: 120 }, (_, i) => ({ questionId: `k${i + 1}`, questionNo: i + 1,
      stem: `Question ${i + 1}?`, image: null, points: 1, optionCount: 2 }))
    const { calls, posted } = server({ links: [{ quizId: 'quiz1', questionNo: 2, productId: 'p1', seq: 0 }],
      questions: many })
    renderPanel()
    expect(await screen.findByTestId('question-product-50')).toBeInTheDocument()
    expect(screen.queryByTestId('question-product-51')).toBeNull()
    expect(screen.getByTestId('question-products-page')).toHaveTextContent('Page 1 of 3')
    await userEvent.click(screen.getByTestId('question-products-next'))
    await userEvent.click(await screen.findByTestId('question-product-60'))
    await userEvent.click(await screen.findByRole('option', { name: 'Cake' }))
    expect(posted.filter((p) => p.path === '/api/queries/qb.sponsor.quiz-questions').map((p) => p.body.offset))
      .toEqual([0, 50])
    await userEvent.click(screen.getByTestId('quiz-products-save'))
    await waitFor(() => expect(calls('QB_QUIZ_PRODUCTS_SET')).toEqual([{ quizId: 'quiz1', quizProducts: [],
      questionProducts: [{ questionNo: 2, productId: 'p1' }, { questionNo: 60, productId: 'p2' }] }]))
  })

  it('points to the products page while there are none', async () => {
    server({ products: [] })
    renderPanel()
    expect(await screen.findByTestId('quiz-products-none')).toBeInTheDocument()
  })
})
