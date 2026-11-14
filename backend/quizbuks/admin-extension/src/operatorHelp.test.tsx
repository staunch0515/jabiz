import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import OperatorHelpPage from './OperatorHelpPage'
import { TEMPLATE } from './ids'
import { messages } from './messages'

/** The server as the operators' help sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  faq: [] as Record<string, unknown>[],
  failing: false,
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
  }
})

const entry = (id: string, question: string, answer: string, seq: number) => ({
  faqId: id, category: 'OTHER', seq,
  question: { en: question, ja: `${question}（日本語）` }, answer: { en: answer },
})

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.failing = false
  server.faq = [entry('f1', 'How is a month settled?', 'The platform settles **every day**.', 1),
    entry('f2', 'Where do I see my account?', 'In **Account**.', 2)]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { params?: { q?: string } } = {}) => {
    if (id !== TEMPLATE.operatorFaq) return { items: [], offset: 0, limit: 20 }
    if (server.failing) throw new ApiError(500, { detail: 'down' })
    const q = options.params?.q?.toLowerCase()
    const items = q ? server.faq.filter((row) => JSON.stringify(row.question).toLowerCase().includes(q)
      || JSON.stringify(row.answer).toLowerCase().includes(q)) : server.faq
    return { items, offset: 0, limit: 200 }
  })
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return [{ code: 'OTHER', label: 'Other' }]
    throw new ApiError(404, { detail: 'Not found' })
  })
})

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter><OperatorHelpPage /></MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the operators\' help', () => {
  it('lists the questions folded, opens an answer as Markdown and is accessible', async () => {
    const { container } = renderPage()
    const entries = await screen.findAllByTestId('operator-faq-entry')
    expect(entries).toHaveLength(2)
    expect(within(entries[0]!).getByText('How is a month settled?')).toBeInTheDocument()
    expect(within(entries[0]!).getByText('Other')).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(within(entries[0]!).getByText('How is a month settled?'))
    expect(await within(entries[0]!).findByText('every day')).toBeInTheDocument()
    await expectAccessible(container)
  })

  it('searches by text and says so when nothing contains it', async () => {
    renderPage()
    await screen.findAllByTestId('operator-faq-entry')
    const user = userEvent.setup()
    await user.type(screen.getByTestId('operator-help-search'), 'account')
    await waitFor(() => expect(screen.getAllByTestId('operator-faq-entry')).toHaveLength(1))
    expect(server.runQuery).toHaveBeenLastCalledWith(TEMPLATE.operatorFaq, expect.objectContaining({
      params: { q: 'account' } }))
    await user.clear(screen.getByTestId('operator-help-search'))
    await user.type(screen.getByTestId('operator-help-search'), 'zzz')
    expect(await screen.findByTestId('operator-help-empty')).toHaveTextContent('No answer contains that text')
  })

  it('says there is nothing yet when no question is published', async () => {
    server.faq = []
    renderPage()
    expect(await screen.findByTestId('operator-help-empty')).toHaveTextContent('There is nothing here yet')
  })

  it('says it could not load, with a way to try again', async () => {
    server.failing = true
    renderPage()
    expect(await screen.findByText('Could not load this. Try again.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})
