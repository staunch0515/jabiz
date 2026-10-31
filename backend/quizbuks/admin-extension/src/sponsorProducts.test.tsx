import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import SponsorPageReviewPage from './SponsorPageReviewPage'
import SponsorProductsPage from './SponsorProductsPage'
import { PERMISSION, SPONSOR_PRODUCT_PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'
import { productMessages } from './product-messages'
import { formatShopPrice } from './product-api'

type Row = Record<string, unknown>

/** The server as the products pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  products: [] as Record<string, unknown>[],
  runQuery: vi.fn(),
  get: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    ApprovalPanel: () => <div data-testid="approval-panel" />,
    FilePreview: () => <img data-testid="file-preview" alt="" />,
  }
})

const ANN = '0192f0a0-0000-7000-8000-00000000c001'

const product = (change: Row = {}): Row => ({ productId: 'p1', userId: ANN, name: 'Rye loaf', description: null,
  image: null, price: '1200', currency: 'JPY', shopUrl: 'https://shop.example/rye', status: 'LISTED', seq: 0,
  takenDownAt: null, takedownReason: null, takedownOperatorId: null, editedAt: '2026-10-30T01:00:00Z', company: 'Acme',
  handle: 'acme', pageStatus: 'PUBLISHED', ...change })

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.publicationRead, PERMISSION.contentWrite])
  server.products = [product(), product({ productId: 'p2', name: 'Cake', price: '9.5', currency: 'USD',
    status: 'TAKEN_DOWN', takedownReason: 'Misleading', shopUrl: 'javascript:alert(1)' })]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: {
    count?: boolean; params?: Row; filters?: { field: string; value: unknown }[]
  } = {}) => {
    if (id === TEMPLATE.sponsorStats) {
      return { items: [{ views: '1200', clicks: '34' }], offset: 0, limit: 1 }
    }
    let items = id === TEMPLATE.sponsorProducts ? server.products
      : id === TEMPLATE.sponsorPages ? [{ userId: ANN, handle: 'acme', language: 'en', headline: 'Bread',
        status: 'PUBLISHED', company: 'Acme', reviewRequired: false }] : []
    const status = options.filters?.find((f) => f.field === 'status')?.value
    if (status) items = items.filter((row) => row.status === status)
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') {
      return [{ code: 'LISTED', label: 'Shown' }, { code: 'UNLISTED', label: 'Hidden' },
        { code: 'TAKEN_DOWN', label: 'Taken down' }, { code: 'PUBLISHED', label: 'Published' }]
    }
    throw new ApiError(404, { detail: 'Not found' })
  })
})

function renderAt(path: string, route: string, element: ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path={route} element={element} />
            <Route path="*" element={<p data-testid="elsewhere">elsewhere</p>} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe("sponsors' products (Q60b-3)", () => {
  it('have every text in every language, and show prices in their currency', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(productMessages.en)
    expect(keys(productMessages.zh)).toEqual(english)
    expect(keys(productMessages.ja)).toEqual(english)
    expect(formatShopPrice('9.5', 'USD', 'en')).toBe('$9.50')
    expect(formatShopPrice('1200', 'JPY', 'en')).toMatch(/1,200/)
  })

  it("are listed with their sponsor, price, state and headquarters' takedown or restore", async () => {
    const { container } = renderAt('/qb/sponsor-products', '/qb/sponsor-products', <SponsorProductsPage />)
    const rows = await screen.findAllByTestId('sponsor-product-row')
    expect(rows.map((row) => row.dataset.status)).toEqual(['LISTED', 'TAKEN_DOWN'])
    expect(within(rows[0]).getByTestId('sponsor-product-sponsor')).toHaveAttribute('href', `/qb/sponsor-pages/${ANN}`)
    expect(within(rows[0]).getByTestId('sponsor-product-shop')).toHaveAttribute('href', 'https://shop.example/rye')
    expect(within(rows[0]).getByTestId('sponsor-product-takedown')).toHaveAttribute('href',
      `/processes/${SPONSOR_PRODUCT_PROCESS.takedown}/1?productId=p1`)
    // A link that is not https is shown, not made a link; one taken down shows why and is put back.
    expect(within(rows[1]).queryByTestId('sponsor-product-shop')).toBeNull()
    expect(within(rows[1]).getByTestId('sponsor-product-reason')).toHaveTextContent('Misleading')
    expect(within(rows[1]).getByTestId('sponsor-product-restore')).toHaveAttribute('href',
      `/processes/${SPONSOR_PRODUCT_PROCESS.restore}/1?productId=p2`)
    await expectAccessible(container)

    const user = userEvent.setup()
    await user.click(screen.getByTestId('sponsor-products-status'))
    await user.click(await screen.findByRole('option', { name: 'Taken down' }))
    expect(await screen.findAllByTestId('sponsor-product-row')).toHaveLength(1)
  })

  it("use the operator's processes in an operator's session, and none for a reader", async () => {
    server.permissions = new Set([PERMISSION.publicationRead, PERMISSION.operatorUse])
    const { unmount } = renderAt('/qb/sponsor-products', '/qb/sponsor-products', <SponsorProductsPage />)
    const rows = await screen.findAllByTestId('sponsor-product-row')
    expect(within(rows[0]).getByTestId('sponsor-product-takedown')).toHaveAttribute('href',
      `/processes/${SPONSOR_PRODUCT_PROCESS.operatorTakedown}/1?productId=p1`)
    // Headquarters' takedown is not the operator's to put back.
    expect(within(rows[1]).queryByTestId('sponsor-product-restore')).toBeNull()
    expect(within(rows[1]).getByTestId('sponsor-product-by-hq')).toBeInTheDocument()
    unmount()
    server.permissions = new Set([PERMISSION.publicationRead])
    renderAt('/qb/sponsor-products', '/qb/sponsor-products', <SponsorProductsPage />)
    const readOnly = await screen.findAllByTestId('sponsor-product-row')
    expect(within(readOnly[0]).queryByTestId('sponsor-product-takedown')).toBeNull()
  })

  it("are shown with the sponsor's page, for that sponsor only", async () => {
    renderAt(`/qb/sponsor-pages/${ANN}`, '/qb/sponsor-pages/:userId', <SponsorPageReviewPage />)
    const section = await screen.findByTestId('sponsor-page-products')
    expect(await within(section).findAllByTestId('sponsor-product-row')).toHaveLength(2)
    expect(within(section).queryByTestId('sponsor-product-sponsor')).toBeNull()
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.sponsorProducts, expect.objectContaining({
      params: { userId: ANN } }))
  })

  it("show the sponsor's visits of the last 30 days with their page (Q60c-2)", async () => {
    renderAt(`/qb/sponsor-pages/${ANN}`, '/qb/sponsor-pages/:userId', <SponsorPageReviewPage />)
    const visits = await screen.findByTestId('sponsor-page-visits')
    expect(await within(visits).findByText('1,200')).toBeInTheDocument()
    expect(within(visits).getByTestId('sponsor-visits-clicks')).toHaveTextContent('34')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.sponsorStats, expect.objectContaining({
      params: expect.objectContaining({ userId: ANN }) }))
  })
})
