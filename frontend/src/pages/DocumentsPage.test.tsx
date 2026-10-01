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
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ can: () => true }) }))
const runProcess = vi.fn()
vi.mock('../lib/calls', () => ({ runProcess: (...args: unknown[]) => runProcess(...args) }))

const RUN = {
  runId: 'd-1', layoutId: 'commerce.order_confirmation', title: 'Order confirmation', documentNo: 'SO-1',
  subjectEntity: 'SalesOrder', subjectId: 'o-1', issuedBy: 'alice', issuedTime: '2026-02-05T15:00:00Z', pages: 2,
  pdfHash: '9f2741d77d343f5ede1c4130251ff5848eb9b3dabcfb1e1eb4f402d774be36e9', recomputable: true,
  recipients: ['ap@customer.example'],
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

  it('opens a document onto where it was sent and sends it again', async () => {
    get.mockImplementation((path: string) => path === '/api/documents/runs' ? [RUN] : {
      run: RUN,
      deliveries: [
        { deliveryId: 'x-1', address: 'ap@customer.example', outcome: 'SENT', attempts: 1, requestedBy: 'alice',
          createdTime: '2026-02-05T15:01:00Z' },
        { deliveryId: 'x-2', address: 'boss@customer.example', outcome: 'FAILED', attempts: 2, requestedBy: 'bob',
          createdTime: '2026-02-05T15:02:00Z', lastError: 'mail server busy' },
      ],
    })
    runProcess.mockResolvedValue({ addresses: ['ap@customer.example'], deliveryIds: ['x-3'] })
    renderPage('/documents')
    await screen.findByText(/SO-1/)
    fireEvent.click(document.querySelector('.ant-table-row-expand-icon') as Element)
    expect((await screen.findByTestId('delivery-x-1')).textContent).toBe('Sent')
    expect(screen.getByTestId('delivery-x-2').textContent).toBe('Failed, retrying')

    fireEvent.click(screen.getByTestId('document-send-d-1'))
    fireEvent.click(await screen.findByRole('button', { name: 'Send' }))
    await waitFor(() => expect(runProcess).toHaveBeenCalledWith('DOCUMENT_SEND',
      { runId: 'd-1', to: ['ap@customer.example'] }))
  })
})
