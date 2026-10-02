import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import MatchingPage from './MatchingPage'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({
  propose: vi.fn(), accept: vi.fn(), match: vi.fn(), unmatch: vi.fn(), entry: vi.fn(), lines: vi.fn(), items: vi.fn(),
  history: vi.fn(), permissions: new Set<string>(),
}))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadBanks: () => Promise.resolve([{ bankCode: 'OPERATING', glAccount: '1010', bankName: 'Lakeside National Bank' }]),
  loadOpenLines: calls.lines,
  loadBookItems: calls.items,
  loadHistory: calls.history,
  propose: calls.propose,
  acceptProposals: calls.accept,
  matchByHand: calls.match,
  unmatch: calls.unmatch,
  entryFromLine: calls.entry,
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
}))

const page = (items: unknown[]) => ({ items, offset: 0, limit: 500, total: items.length })

beforeAll(setUpTexts)

beforeEach(() => {
  calls.permissions = new Set(['fin.bank.activity.read', 'fin.bank.reconcile'])
  calls.propose.mockReset().mockResolvedValue({ openLines: 1, openItems: 2, proposals: [
    { lineId: 'l2', valueDate: '2026-01-05', bankReference: 'BNK-0002', description: 'DEPOSIT ACME ROBOTICS INC',
      amount: 32475, confidence: 85, reasons: ['amount equal', 'same day'],
      items: [{ kind: 'LEDGER', id: 't1', date: '2026-01-05', amount: 32475, documentNo: 'RCPT-0001' }] },
    { lineId: 'l5', valueDate: '2026-01-16', bankReference: 'BNK-0005', description: 'DEPOSIT CASCADE', amount: 24025,
      confidence: 70, reasons: ['amount equal'],
      items: [{ kind: 'LEDGER', id: 't2', date: '2026-01-16', amount: 24025, documentNo: 'RCPT-0002' }] },
  ] })
  calls.lines.mockReset().mockResolvedValue(page([
    { lineId: 'l4', valueDate: '2026-01-15', bankReference: 'BNK-0004', description: 'ACH DEBIT', amount: '-15000.00' },
    { lineId: 'l9', valueDate: '2026-01-31', bankReference: 'BNK-0009', description: 'ACCOUNT SERVICE FEE', amount: -45 },
  ]))
  calls.items.mockReset().mockResolvedValue(page([
    { refKind: 'LEDGER', refId: 'a', itemDate: '2026-01-15', documentNo: 'TRF-0001', amount: '-10000.00' },
    { refKind: 'LEDGER', refId: 'b', itemDate: '2026-01-15', documentNo: 'TRF-0002', amount: '-5000.00' },
  ]))
  calls.history.mockReset().mockResolvedValue(page([
    { matchId: 'm1', action: 'MATCH', method: 'AUTO', amount: 3200, confidence: 75, actor: 'accountant',
      actionTime: '2026-01-31T09:00:00Z', statementItems: 'BNK-0001', bookItems: 'CHK-1045' },
    { matchId: 'm0', action: 'MATCH', method: 'MANUAL', amount: 1, actor: 'accountant',
      actionTime: '2026-01-31T09:00:00Z', statementItems: 'BNK-0003', bookItems: 'X' },
    { matchId: 'u0', action: 'UNMATCH', reversesMatchId: 'm0', amount: 1, actor: 'accountant', reason: 'Wrong',
      actionTime: '2026-01-31T09:00:00Z', statementItems: 'BNK-0003', bookItems: 'X' },
  ]))
  calls.accept.mockReset().mockResolvedValue({ matched: 1 })
  calls.match.mockReset().mockResolvedValue({ matchId: 'm2', amount: -15000 })
  calls.unmatch.mockReset().mockResolvedValue({ undoId: 'u1', matchId: 'm1' })
  calls.entry.mockReset().mockResolvedValue({ entryId: 'e1', entryNo: 'BANK-FEE-2601', matchId: 'm3' })
})

const render = () => renderAt('/bank/matching', [{ path: '/bank/matching', element: <MatchingPage /> }])

describe('the bank matching page', () => {
  it('accepts the proposals chosen, with their confidence and reasons', async () => {
    render()
    const table = await screen.findByTestId('proposal-table')
    expect(await within(table).findByText('RCPT-0001')).toBeInTheDocument()
    expect(within(table).getByText('amount equal; same day')).toBeInTheDocument()
    expect(screen.getByTestId('accept-proposals')).toHaveTextContent('Accept 2 selected')
    // The second is left out.
    await userEvent.click(within(table).getAllByRole('checkbox')[2])
    await userEvent.click(screen.getByTestId('accept-proposals'))
    await waitFor(() => expect(calls.accept).toHaveBeenCalledTimes(1))
    const [bank, proposals] = calls.accept.mock.calls[0]
    expect(bank).toBe('OPERATING')
    expect(proposals.map((p: { lineId: string }) => p.lineId)).toEqual(['l2'])
  })

  it('matches lines and items by hand only when their totals agree', async () => {
    render()
    const lines = await screen.findByTestId('line-table')
    const items = screen.getByTestId('item-table')
    await within(lines).findByText('BNK-0004')
    await within(items).findByText('TRF-0001')
    const button = screen.getByTestId('match-by-hand')
    await userEvent.click(within(lines).getAllByRole('checkbox')[1])
    await userEvent.click(within(items).getAllByRole('checkbox')[1])
    expect(button).toBeDisabled()
    await userEvent.click(within(items).getAllByRole('checkbox')[2])
    expect(screen.getByTestId('hand-totals')).toHaveTextContent('Lines -15,000.00 · book items -15,000.00')
    await userEvent.type(screen.getByLabelText('Reason'), 'Two sweeps')
    await userEvent.click(button)
    await waitFor(() => expect(calls.match).toHaveBeenCalledTimes(1))
    const [bank, lineIds, picked, reason] = calls.match.mock.calls[0]
    expect([bank, lineIds, picked.map((i: { refId: string }) => i.refId), reason])
      .toEqual(['OPERATING', ['l4'], ['a', 'b'], 'Two sweeps'])
  })

  it('makes an entry from a line, and shows the server refusing one', async () => {
    render()
    await userEvent.click(await screen.findByTestId('make-entry-BNK-0009'))
    await waitFor(() => expect(calls.entry).toHaveBeenCalledWith('l9', null))
    expect(await screen.findByText('BANK-FEE-2601 posted and matched')).toBeInTheDocument()
  })

  it('undoes a match not undone yet, with a reason', async () => {
    render()
    const history = await screen.findByTestId('history-table')
    await within(history).findByText('CHK-1045')
    // The undone match offers no undo.
    expect(screen.queryByTestId('undo-BNK-0003')).not.toBeInTheDocument()
    await userEvent.click(screen.getByTestId('undo-BNK-0001'))
    expect(await screen.findByTestId('undo-confirm')).toBeDisabled()
    await userEvent.type(screen.getByTestId('undo-reason'), 'Checking')
    await userEvent.click(screen.getByTestId('undo-confirm'))
    await waitFor(() => expect(calls.unmatch).toHaveBeenCalledWith('m1', 'Checking'))
  })

  it('counts only the proposals still made after the page is read again', async () => {
    render()
    const table = await screen.findByTestId('proposal-table')
    await within(table).findByText('RCPT-0001')
    // Only the first chosen; then another change takes it away and the page reads the proposals again.
    await userEvent.click(within(table).getAllByRole('checkbox')[2])
    expect(screen.getByTestId('accept-proposals')).toHaveTextContent('Accept 1 selected')
    calls.propose.mockResolvedValue({ openLines: 0, openItems: 0, proposals: [] })
    await userEvent.click(await screen.findByTestId('make-entry-BNK-0009'))
    await waitFor(() => expect(screen.getByTestId('accept-proposals')).toHaveTextContent('Accept 0 selected'))
    expect(screen.getByTestId('accept-proposals')).toBeDisabled()
  })

  it('shows a reader the lines, items and history, and nothing to change them', async () => {
    calls.permissions = new Set(['fin.bank.activity.read'])
    render()
    await within(await screen.findByTestId('line-table')).findByText('BNK-0004')
    expect(screen.queryByTestId('accept-proposals')).not.toBeInTheDocument()
    expect(screen.queryByTestId('match-by-hand')).not.toBeInTheDocument()
    expect(screen.queryByTestId('undo-BNK-0001')).not.toBeInTheDocument()
    expect(within(screen.getByTestId('line-table')).queryAllByRole('checkbox')).toHaveLength(0)
    expect(calls.propose).not.toHaveBeenCalled()
  })
})
