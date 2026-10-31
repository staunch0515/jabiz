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
  answers?: Record<string, () => Response> } = {}) {
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
      case '/api/datasets/urn:jabiz:dataset:me:QbSponsorProduct/query':
        return json(200, { items: (options.products ?? [RYE, CAKE]).map((row) => ({ id: row.productId, version: 1,
          attributes: row })), offset: 0, limit: 200 })
      case '/api/datasets/urn:jabiz:dataset:me:QbQuizProduct/query':
        return json(200, { items: (options.links ?? []).map((row, i) => ({ id: `l${i}`, version: 1, attributes: row })),
          offset: 0, limit: 200 })
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
  return { calls }
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

describe("the sponsor's products (Q60b)", () => {
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
    window.history.replaceState(null, '', '/products')
    render(<App />)
    const rows = await screen.findAllByTestId('product')
    expect(rows.map((row) => row.dataset.status)).toEqual(['LISTED', 'TAKEN_DOWN'])
    expect(within(rows[1]).getByTestId('product-takedown')).toHaveTextContent('Misleading')
    await expectAccessible()
  })

  it('adds one, last, with its price as typed', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', '/products')
    render(<App />)
    await userEvent.click(await screen.findByTestId('product-add'))
    const dialog = await screen.findByTestId('product-dialog')
    await userEvent.type(within(dialog).getByTestId('product-name'), 'Baguette')
    await userEvent.type(within(dialog).getByTestId('product-price'), '350')
    await userEvent.type(within(dialog).getByTestId('product-shop'), 'https://shop.example/baguette')
    await userEvent.click(within(dialog).getByTestId('product-save'))
    await waitFor(() => expect(calls('QB_PRODUCT_SAVE')).toHaveLength(1))
    expect(calls('QB_PRODUCT_SAVE')[0]).toMatchObject({ productId: null, name: 'Baguette', price: '350',
      currency: 'JPY', shopUrl: 'https://shop.example/baguette', listed: true, seq: 2, baseRevision: null })
  })

  it("shows the server's refusal of a price at the field", async () => {
    server({ answers: { QB_PRODUCT_SAVE: () => json(422, { title: 'Unprocessable', status: 422, violations: [
      { field: 'price', ruleCode: 'QB_PRODUCT_PRICE', message: 'No decimals in yen.' }] }) } })
    window.history.replaceState(null, '', '/products')
    render(<App />)
    await userEvent.click((await screen.findAllByTestId('product-edit'))[0])
    const dialog = await screen.findByTestId('product-dialog')
    await userEvent.click(within(dialog).getByTestId('product-save'))
    expect(await within(dialog).findByText('No decimals in yen.')).toBeInTheDocument()
    expect(within(dialog).getByTestId('product-price')).toHaveAttribute('aria-invalid', 'true')
  })

  it('moves one by swapping places with its neighbour, and deletes one on confirmation', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', '/products')
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
    { questionId: 'q1', stem: 'Capital of France?', image: null, points: 2, options: [], selection: null },
    { questionId: 'q2', stem: 'Capital of Japan?', image: null, points: 3, options: [], selection: null },
  ]

  function renderPanel() {
    return render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <QuizProductsPanel quizId="quiz1" questions={questions} />
      </QueryClientProvider>,
    )
  }

  it('starts from the links saved and sends the whole quiz’s again', async () => {
    const { calls } = server({ links: [{ quizId: 'quiz1', questionNo: 0, productId: 'p2', seq: 0 },
      { quizId: 'quiz1', questionNo: 2, productId: 'p1', seq: 0 },
      { quizId: 'quiz1', questionNo: 3, productId: 'p1', seq: 0 }] })
    renderPanel()
    expect(await screen.findByTestId('quiz-product-slot-1')).toHaveTextContent('Cake')
    expect(screen.getByTestId('quiz-product-slot-2')).toHaveTextContent('None')
    expect(screen.getByTestId('question-product-2')).toHaveTextContent('Rye loaf')
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

  it('points to the products page while there are none', async () => {
    server({ products: [] })
    renderPanel()
    expect(await screen.findByTestId('quiz-products-none')).toBeInTheDocument()
  })
})
