import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@jabiz/admin'
import type { LoadedJournal } from './api'
import JournalEntryRoute from './JournalEntryRoute'
import { renderAt, setUpTexts } from './testing'

const calls = vi.hoisted(() => ({
  saveJournal: vi.fn(),
  submitJournal: vi.fn(),
  loadJournal: vi.fn(),
  runProcess: vi.fn(),
  permissions: new Set<string>(),
}))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadAccounts: async () =>
    new Map([
      ['2100', { accountCode: '2100', accountName: 'Accrued liabilities', active: true, summary: false }],
      ['6400', { accountCode: '6400', accountName: 'Professional fees', active: true, summary: false }],
    ]),
  loadDimensions: async () => ({ departments: new Set(['ADMIN']), locations: new Set<string>() }),
  loadJournal: calls.loadJournal,
  saveJournal: calls.saveJournal,
  submitJournal: calls.submitJournal,
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  runProcess: calls.runProcess,
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
  ApprovalPanel: ({ requestId }: { requestId: string }) => <div data-testid="approval-panel">{requestId}</div>,
}))

const ROUTES = [
  { path: '/gl/journals/new', element: <JournalEntryRoute /> },
  { path: '/gl/journals/:journalId', element: <JournalEntryRoute /> },
]

const STORED: LoadedJournal = {
  journal: {
    journalId: 'j-1',
    journalNo: 'JE-0002',
    postingDate: '2026-01-31',
    description: 'Accrue annual audit fee',
    source: 'MANUAL',
    status: 'SUBMITTED',
    preparer: 'accountant',
    totalDebit: 25000,
    totalCredit: 25000,
    approvalRequestId: 'r-1',
    periodKey: '2026-01',
  },
  version: 2,
  lines: [
    { lineNo: 1, accountCode: '6400', debit: 25000, memo: 'audit' },
    { lineNo: 2, accountCode: '2100', credit: 25000 },
  ],
  attachments: [],
}

const cell = (column: string, line: number) => screen.getByLabelText(`${column}, line ${line}`) as HTMLInputElement

beforeAll(setUpTexts)

beforeEach(() => {
  for (const fn of [calls.saveJournal, calls.submitJournal, calls.loadJournal, calls.runProcess]) fn.mockReset()
  calls.permissions = new Set(['fin.journal.prepare', 'fin.journal.read', 'fin.journal.attach'])
})

async function fillNewEntry() {
  renderAt('/gl/journals/new', ROUTES)
  await userEvent.type(screen.getByLabelText('Posting date'), '2026-01-31')
  await userEvent.type(screen.getByLabelText('Description'), 'Accrue annual audit fee')
  await userEvent.type(cell('Account', 1), '6400')
  await userEvent.type(cell('Debit', 1), '25000')
  await userEvent.type(cell('Account', 3), '2100')
  await userEvent.type(cell('Credit', 3), '25,000')
}

describe('JournalEntryPage', () => {
  it('saves a new entry with Ctrl+S, without the blank lines, and opens it', async () => {
    calls.saveJournal.mockResolvedValue({ journalId: 'j-9', status: 'DRAFT' })
    calls.loadJournal.mockResolvedValue({ ...STORED, journal: { ...STORED.journal, journalId: 'j-9', status: 'DRAFT', journalNo: null } })
    await fillNewEntry()
    fireEvent.keyDown(cell('Memo', 1), { key: 's', ctrlKey: true })

    await waitFor(() => expect(calls.saveJournal).toHaveBeenCalledTimes(1))
    const [input, key] = calls.saveJournal.mock.calls[0]
    expect(input).toMatchObject({
      journalId: undefined,
      postingDate: '2026-01-31',
      description: 'Accrue annual audit fee',
      lines: [
        { accountCode: '6400', debit: '25000.00', credit: null },
        { accountCode: '2100', debit: null, credit: '25000.00' },
      ],
    })
    expect(key).toEqual(expect.any(String))
    await waitFor(() => expect(calls.loadJournal).toHaveBeenCalledWith('j-9'))
  }, 20_000)

  it('submits with Ctrl+Enter and shows each refusal on the cell it names, also once the saved entry loads', async () => {
    calls.saveJournal.mockResolvedValue({ journalId: 'j-9', status: 'DRAFT' })
    calls.loadJournal.mockResolvedValue({
      ...STORED,
      journal: { ...STORED.journal, journalId: 'j-9', status: 'DRAFT', journalNo: null, approvalRequestId: null },
      lines: [
        { lineNo: 1, accountCode: '6400', debit: 25000 },
        { lineNo: 2, accountCode: '2100', credit: 25000 },
      ],
    })
    calls.submitJournal.mockRejectedValue(
      new ApiError(422, {
        detail: 'refused',
        violations: [
          { field: 'lines[1].accountCode', ruleCode: 'FIN_JOURNAL_CONTROL_ACCOUNT', message: 'Account 2100 is a control account' },
          { field: 'postingDate', ruleCode: 'FIN_PERIOD_CLOSED', message: 'Period closed' },
        ],
      }),
    )
    await fillNewEntry()
    fireEvent.keyDown(cell('Memo', 1), { key: 'Enter', ctrlKey: true })

    await waitFor(() => expect(calls.submitJournal).toHaveBeenCalledWith('j-9', expect.any(String)))
    // Saved, the lines show without the blank one between them: the server's second line is grid line 2.
    expect(await screen.findByText('Period closed')).toBeInTheDocument()
    await waitFor(() => expect(calls.loadJournal).toHaveBeenCalledWith('j-9'))
    expect(cell('Account', 2).value).toBe('2100')
    expect(cell('Account', 2)).toHaveAttribute('aria-invalid', 'true')
    expect(cell('Account', 2)).toHaveAttribute('title', 'Account 2100 is a control account')
  }, 20_000)

  it('shows the submitted entry even when the read of the just-saved entry was still on its way', async () => {
    const draft: LoadedJournal = { ...STORED, version: 1, journal: { ...STORED.journal, journalId: 'j-9', status: 'DRAFT', journalNo: null } }
    let answerFirstRead: (value: LoadedJournal) => void = () => undefined
    calls.saveJournal.mockResolvedValue({ journalId: 'j-9', status: 'DRAFT' })
    // The read the page starts on opening the saved entry answers only after the submission: with the draft.
    calls.loadJournal
      .mockImplementationOnce(() => new Promise<LoadedJournal>((resolve) => (answerFirstRead = resolve)))
      .mockResolvedValue({ ...STORED, journal: { ...STORED.journal, journalId: 'j-9' } })
    calls.submitJournal.mockImplementation(async () => {
      await waitFor(() => expect(calls.loadJournal).toHaveBeenCalledTimes(1))
      return { journalId: 'j-9', journalNo: 'JE-0002', status: 'SUBMITTED' }
    })
    await fillNewEntry()
    fireEvent.keyDown(cell('Memo', 1), { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(calls.submitJournal).toHaveBeenCalledTimes(1))
    answerFirstRead(draft)

    await waitFor(() => expect(screen.getByTestId('journal-status')).toHaveTextContent('Waiting for approval'))
    expect(screen.getByTestId('page-title')).toHaveTextContent('Journal entry JE-0002')
  }, 20_000)

  it('submits a rejected entry again as a new request', async () => {
    calls.loadJournal.mockResolvedValue({ ...STORED, journal: { ...STORED.journal, status: 'REJECTED' } })
    calls.submitJournal.mockResolvedValue({ journalId: 'j-1', journalNo: 'JE-0002', status: 'SUBMITTED' })
    renderAt('/gl/journals/j-1', ROUTES)
    await screen.findByText('Journal entry JE-0002')
    const submit = () => screen.getByText('Submit').closest('button') as HTMLButtonElement
    await userEvent.click(submit())
    await waitFor(() => expect(calls.submitJournal).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(submit()).not.toBeDisabled())
    await userEvent.click(submit())
    await waitFor(() => expect(calls.submitJournal).toHaveBeenCalledTimes(2))
    const [first, second] = calls.submitJournal.mock.calls.map((call) => call[1])
    expect(second).not.toBe(first)
    expect(calls.saveJournal).not.toHaveBeenCalled()
  }, 20_000)

  it('shows a submitted entry with its approval to approvers; changing it warns it returns to draft', async () => {
    calls.loadJournal.mockResolvedValue(STORED)
    calls.permissions.add('approval.decide')
    renderAt('/gl/journals/j-1', ROUTES)

    expect(await screen.findByText('Journal entry JE-0002')).toBeInTheDocument()
    expect(screen.getByTestId('journal-status')).toHaveTextContent('Waiting for approval')
    expect(cell('Debit', 1).value).toBe('25000.00')
    expect(screen.getByTestId('approval-panel')).toHaveTextContent('r-1')
    expect(screen.getByTestId('total-difference')).toHaveTextContent('Balanced')

    await userEvent.type(cell('Memo', 2), 'x')
    expect(screen.getByText(/returns this entry to draft/)).toBeInTheDocument()
  }, 20_000)

  it('a posted entry is read-only and offers its reversal', async () => {
    calls.loadJournal.mockResolvedValue({
      ...STORED,
      journal: { ...STORED.journal, status: 'POSTED', glNo: 'GJ-MAN-2026-000002', approvalRequestId: null },
    })
    calls.runProcess.mockResolvedValue({ journalId: 'j-2', journalNo: 'JE-0005', status: 'POSTED' })
    renderAt('/gl/journals/j-1', ROUTES)

    expect(await screen.findByTestId('journal-gl-no')).toHaveTextContent('GJ-MAN-2026-000002')
    expect(cell('Account', 1)).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: 'Submit' })).not.toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Posting date of the reversal'), '2026-02-01')
    await userEvent.click(screen.getByRole('button', { name: 'Reverse' }))
    await waitFor(() =>
      expect(calls.runProcess).toHaveBeenCalledWith('FIN_JOURNAL_REVERSE', { journalId: 'j-1', postingDate: '2026-02-01' }),
    )
  }, 20_000)

  it('offers no editing to those who may not prepare entries', async () => {
    calls.permissions = new Set(['fin.journal.read'])
    calls.loadJournal.mockResolvedValue({ ...STORED, journal: { ...STORED.journal, status: 'DRAFT' } })
    renderAt('/gl/journals/j-1', ROUTES)
    await screen.findByText('Journal entry JE-0002')
    expect(cell('Account', 1)).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument()
  })
})
