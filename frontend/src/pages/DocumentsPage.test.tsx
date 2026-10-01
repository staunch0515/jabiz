import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import DocumentsPage from './DocumentsPage'

const get = vi.fn()
const post = vi.fn()
const downloadDocument = vi.fn()

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { GET: (...args: unknown[]) => get(...args), POST: (...args: unknown[]) => post(...args) },
  unwrap: (value: unknown) => value,
}))
vi.mock('../api/documents', () => ({ downloadDocument: (...args: unknown[]) => downloadDocument(...args) }))

const RUN = {
  runId: 'd-1', layoutId: 'commerce.order_confirmation', title: 'Order confirmation', documentNo: 'SO-1',
  subjectEntity: 'SalesOrder', subjectId: 'o-1', issuedBy: 'alice', issuedTime: '2026-02-05T15:00:00Z', pages: 2,
  pdfHash: '9f2741d77d343f5ede1c4130251ff5848eb9b3dabcfb1e1eb4f402d774be36e9', recomputable: true,
}

function renderPage(path: string) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <App>
        <MemoryRouter initialEntries={[path]}>
          <DocumentsPage />
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  )
}

describe('DocumentsPage', () => {
  beforeEach(async () => {
    get.mockReset()
    post.mockReset()
    downloadDocument.mockReset()
    await i18n.changeLanguage('en')
  })

  it('lists the issued documents of a subject, saves one as issued and verifies it', async () => {
    get.mockResolvedValue([RUN])
    post.mockResolvedValue({ verdict: 'identical', copyIntact: true, recomputable: true })
    renderPage('/documents?subject=o-1')

    expect(await screen.findByText(/SO-1/)).toBeTruthy()
    expect(get.mock.calls[0][1]).toEqual({ params: { query: { layout: undefined, subject: 'o-1', limit: 200 } } })
    expect(screen.getByText('9f2741d77d34')).toBeTruthy()

    fireEvent.click(screen.getByTestId('document-verify-d-1'))
    await waitFor(() => expect(screen.getByTestId('verdict-d-1').textContent).toBe('Matches the data'))
    expect(screen.queryByTestId('copy-d-1')).toBeNull()

    fireEvent.click(screen.getByTestId('document-download-d-1'))
    await waitFor(() => expect(downloadDocument).toHaveBeenCalledWith('d-1'))
  })

  it('says when the kept copy was altered', async () => {
    get.mockResolvedValue([RUN])
    post.mockResolvedValue({ verdict: 'layout_changed', copyIntact: false, recomputable: true })
    renderPage('/documents')
    fireEvent.click(await screen.findByTestId('document-verify-d-1'))
    expect((await screen.findByTestId('copy-d-1')).textContent).toBe('The kept PDF was altered')
    expect(screen.getByTestId('verdict-d-1').textContent).toBe('Layout changed')
  })
})
