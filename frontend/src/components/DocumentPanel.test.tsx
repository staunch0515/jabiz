import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/problem'
import i18n from '../i18n'
import DocumentPanel from './DocumentPanel'

const get = vi.fn()
const runProcess = vi.fn()
const downloadDocument = vi.fn()
const previewDocument = vi.fn()
let permissions = new Set<string>()

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { GET: (...args: unknown[]) => get(...args) },
  unwrap: (value: unknown) => value,
}))
vi.mock('../lib/calls', () => ({ runProcess: (...args: unknown[]) => runProcess(...args) }))
vi.mock('../api/documents', () => ({
  downloadDocument: (...args: unknown[]) => downloadDocument(...args),
  previewDocument: (...args: unknown[]) => previewDocument(...args),
}))
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ can: (p: string) => permissions.has('*') || permissions.has(p) }),
}))

const RUN = { runId: 'd-1', layoutId: 'commerce.order_confirmation', title: 'Order confirmation',
  documentNo: 'SO-1', issuedBy: 'alice', issuedTime: '2026-02-05T15:00:00Z', pages: 1,
  recipients: ['ap@customer.example'] }

function renderPanel(props: Partial<Parameters<typeof DocumentPanel>[0]> = {}) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <App>
        <DocumentPanel layoutId="commerce.order_confirmation" params={{ orderId: 'o-1' }} subjectId="o-1" {...props} />
      </App>
    </QueryClientProvider>,
  )
}

describe('DocumentPanel', () => {
  beforeEach(async () => {
    get.mockReset()
    runProcess.mockReset()
    downloadDocument.mockReset()
    previewDocument.mockReset()
    permissions = new Set(['document.issue', 'document.archive.read', 'document.send'])
    await i18n.changeLanguage('en')
  })

  it('lists the copies of the subject, saves one as issued and previews the document', async () => {
    get.mockResolvedValue([RUN])
    renderPanel()
    expect(await screen.findByText('SO-1')).toBeTruthy()
    expect(get.mock.calls[0][1]).toEqual({ params: { query: { layout: 'commerce.order_confirmation',
      subject: 'o-1', limit: 50 } } })
    fireEvent.click(screen.getByTestId('document-download-d-1'))
    await waitFor(() => expect(downloadDocument).toHaveBeenCalledWith('d-1'))
    fireEvent.click(screen.getByTestId('document-preview'))
    await waitFor(() => expect(previewDocument).toHaveBeenCalledWith('commerce.order_confirmation',
      { params: { orderId: 'o-1' } }))
  })

  it('issues through the application process and lists the new copy', async () => {
    get.mockResolvedValueOnce([]).mockResolvedValue([RUN])
    runProcess.mockResolvedValue({ runId: 'd-1', documentNo: 'SO-1' })
    const onIssued = vi.fn()
    renderPanel({ issue: { process: 'ORDER_CONFIRMATION_ISSUE', input: { orderId: 'o-1' } }, onIssued })
    expect(await screen.findByText('No document has been issued yet.')).toBeTruthy()
    fireEvent.click(screen.getByTestId('document-issue'))
    await waitFor(() => expect(onIssued).toHaveBeenCalledWith('d-1'))
    expect(runProcess).toHaveBeenCalledWith('ORDER_CONFIRMATION_ISSUE', { orderId: 'o-1' })
    expect(await screen.findByText('SO-1')).toBeTruthy()
  })

  it('issues through DOCUMENT_ISSUE by default and shows the refusal of the server', async () => {
    permissions = new Set(['commerce.order.confirm'])
    runProcess.mockRejectedValue(new ApiError(422, { violations: [
      { ruleCode: 'DOCUMENT_NOT_SINGLE', message: 'The document reads one row of x but found 0.' }] }))
    renderPanel()
    // Without the archive permission the copies are not asked for; without issue rights there is no preview.
    expect(screen.queryByTestId('document-preview')).toBeNull()
    fireEvent.click(screen.getByTestId('document-issue'))
    expect((await screen.findByTestId('document-error')).textContent).toContain('found 0')
    expect(runProcess).toHaveBeenCalledWith('DOCUMENT_ISSUE', { layoutId: 'commerce.order_confirmation',
      params: { orderId: 'o-1' } })
    expect(get).not.toHaveBeenCalled()
  })

  it('sends a copy to the addresses its data names, and shows a refusal of another', async () => {
    get.mockResolvedValue([RUN])
    runProcess.mockResolvedValueOnce({ addresses: ['ap@customer.example'], deliveryIds: ['x-1'] })
    renderPanel()
    fireEvent.click(await screen.findByTestId('document-send-d-1'))
    expect(await screen.findByText('ap@customer.example')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(runProcess).toHaveBeenCalledWith('DOCUMENT_SEND',
      { runId: 'd-1', to: ['ap@customer.example'] }))
    await waitFor(() => expect(screen.queryByTestId('document-send-to')).toBeNull())

    runProcess.mockRejectedValueOnce(new ApiError(422, { violations: [{ ruleCode: 'DOCUMENT_RECIPIENT_NOT_ALLOWED',
      message: 'Sending to x@other.example needs the permission to send documents to any address.' }] }))
    fireEvent.click(screen.getByTestId('document-send-d-1'))
    await screen.findByTestId('document-send-to')
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect((await screen.findByTestId('document-send-error')).textContent).toContain('x@other.example')
  })

  it('offers no sending without its permission', async () => {
    permissions = new Set(['document.archive.read'])
    get.mockResolvedValue([RUN])
    renderPanel()
    expect(await screen.findByText('SO-1')).toBeTruthy()
    expect(screen.queryByTestId('document-send-d-1')).toBeNull()
  })
})
