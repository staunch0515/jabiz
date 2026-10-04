import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  ApprovalPanel,
  EXTENSION_NAMESPACE,
  formatAmount,
  formatDate,
  paths,
  runProcess,
  useAuth,
  UserName,
} from '@jabiz/admin'
import { Alert, App, Button, Card, Checkbox, Descriptions, Input, Space, Tag, Typography } from 'antd'
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router'
import {
  DATASETS,
  loadAccounts,
  loadDimensions,
  loadJournal,
  PERMISSIONS,
  PROCESSES,
  saveJournal,
  submitJournal,
  type Journal,
  type JournalInput,
  type JournalOutput,
} from './api'
import AttachmentsCard from './AttachmentsCard'
import {
  checkLine,
  COLUMNS,
  compact,
  fromStored,
  padLines,
  placeViolations,
  record,
  redo,
  startHistory,
  toInputs,
  toTsv,
  undo,
  type GridLine,
  type History,
  type ServerProblems,
} from './grid'
import JournalGrid from './JournalGrid'
import { journalPath, JOURNALS_PATH } from './paths'
import StatusTag from './StatusTag'

interface Header {
  postingDate: string
  documentDate: string
  description: string
  adjusting: boolean
  adjustmentPeriod: boolean
  autoReverseDate: string
}

const EMPTY_HEADER: Header = {
  postingDate: '',
  documentDate: '',
  description: '',
  adjusting: false,
  adjustmentPeriod: false,
  autoReverseDate: '',
}

const STARTING_LINES = 4

function headerOf(journal: Journal): Header {
  return {
    postingDate: journal.postingDate ?? '',
    documentDate: journal.documentDate ?? '',
    description: journal.description ?? '',
    adjusting: Boolean(journal.adjusting),
    adjustmentPeriod: Boolean(journal.adjustmentPeriod),
    autoReverseDate: journal.autoReverseDate ?? '',
  }
}

/** Entries that are not posted can still change; a change of a submitted entry returns it to draft (FIN-GL-014). */
const CHANGEABLE = new Set(['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED'])

/**
 * A journal entry: new, or one that exists (route parameter `journalId`). The header and the grid save through
 * FIN_JOURNAL_SAVE and submit through FIN_JOURNAL_SUBMIT; whatever the server refuses is shown on the cells it
 * names. Ctrl+S saves, Ctrl+Enter submits. Approvers decide here; the controller grants the control-account
 * exception; a posted entry can be reversed. The server decides who may do what; the page only offers it.
 */
export default function JournalEntryPage({ instanceKey }: { instanceKey?: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { message } = App.useApp()
  const { can } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { journalId } = useParams<{ journalId: string }>()
  const formId = useId()

  const accounts = useQuery({ queryKey: ['fin', 'accounts'], queryFn: loadAccounts, staleTime: 60_000 })
  const dimensions = useQuery({ queryKey: ['fin', 'dimensions'], queryFn: loadDimensions, staleTime: 60_000 })
  const [decidedAt, setDecidedAt] = useState<number | null>(null)
  const loaded = useQuery({
    queryKey: ['fin', 'journal', journalId],
    queryFn: () => loadJournal(journalId as string),
    enabled: Boolean(journalId),
    // After a decision the posting follows from the approval event: look again until it shows.
    refetchInterval: (query) =>
      decidedAt !== null && Date.now() - decidedAt < 60_000 && query.state.data?.journal.status !== 'POSTED'
        && query.state.data?.journal.status !== 'REJECTED'
        ? 1500
        : false,
  })
  const journal = loaded.data?.journal
  // A page that began as a new entry holds its content already while the saved entry loads.
  const [startedNew] = useState(!journalId)

  const [header, setHeader] = useState<Header>(EMPTY_HEADER)
  const [history, setHistory] = useState<History>(() => startHistory(padLines([], STARTING_LINES)))
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState<'save' | 'submit' | 'delete' | null>(null)
  const [server, setServer] = useState<ServerProblems | null>(null)
  const idempotency = useRef<{ key: string; body: string } | null>(null)

  // The stored entry fills the form when it arrives, unless the user is changing it.
  const shownVersion = useRef<string | null>(null)
  useEffect(() => {
    if (!loaded.data || dirty) return
    const stamp = `${loaded.data.journal.journalId}:${loaded.data.version}`
    if (shownVersion.current === stamp) return
    shownVersion.current = stamp
    setHeader(headerOf(loaded.data.journal))
    setHistory(startHistory(padLines(fromStored(loaded.data.lines), 2)))
  }, [loaded.data, dirty])

  const lines = history.present
  const editable = (!journal || CHANGEABLE.has(journal.status)) && can(PERMISSIONS.prepare)
  const accountOptions = useMemo(() => [...(accounts.data?.values() ?? [])], [accounts.data])
  const problems = useMemo(
    () => lines.map((line) => checkLine(line, accounts.data, dimensions.data)),
    [lines, accounts.data, dimensions.data],
  )

  const changeLines = useCallback((next: GridLine[]) => {
    setHistory((current) => record(current, next))
    setDirty(true)
    setServer(null)
  }, [])

  /** Undo and redo are changes like any other: unsaved, and the server's refusals no longer apply. */
  const step = (move: (history: History) => History) => {
    setHistory(move)
    setDirty(true)
    setServer(null)
  }

  const changeHeader = (patch: Partial<Header>) => {
    setHeader((current) => ({ ...current, ...patch }))
    setDirty(true)
    setServer(null)
  }

  /**
   * The same request retried after a failure (a timeout) is run once by the server; once a request succeeded, the
   * next one is new even with the same content (submitting a rejected entry again).
   */
  const keyFor = (body: unknown) => {
    const text = JSON.stringify(body)
    if (idempotency.current?.body !== text) idempotency.current = { key: crypto.randomUUID(), body: text }
    return idempotency.current.key
  }
  const succeeded = () => {
    idempotency.current = null
  }

  const showRefusal = (error: unknown, rows: number[]) => {
    if (error instanceof ApiError) {
      const placed = placeViolations(error.violations, rows)
      if (placed.general.length === 0 && placed.cells.size === 0) placed.general.push(error.display)
      setServer(placed)
    } else {
      setServer({ cells: new Map(), general: [String(error)] })
    }
  }

  /**
   * Saves the lines without the blank ones, and shows them so: the server numbers the lines it stores, and its
   * refusals of a later submission name them by that number.
   */
  const save = async (): Promise<JournalOutput | null> => {
    const { inputs, rows } = toInputs(lines)
    const input: JournalInput = {
      journalId: journalId ?? undefined,
      postingDate: header.postingDate,
      documentDate: header.documentDate || null,
      description: header.description,
      adjusting: header.adjusting,
      adjustmentPeriod: header.adjustmentPeriod,
      autoReverseDate: header.autoReverseDate || null,
      lines: inputs,
    }
    try {
      const output = await saveJournal(input, keyFor({ save: input }))
      succeeded()
      setHistory((current) => record(current, compact(current.present)))
      setDirty(false)
      setServer(null)
      shownVersion.current = null
      await queryClient.invalidateQueries({ queryKey: ['fin', 'journal', output.journalId] })
      if (!journalId) navigate(journalPath(output.journalId), { replace: true, state: { entryInstance: instanceKey } })
      return output
    } catch (error) {
      showRefusal(error, rows)
      return null
    }
  }

  const onSave = async () => {
    if (busy || !editable) return
    setBusy('save')
    try {
      if (await save()) message.success(t('journal.saved'))
    } finally {
      setBusy(null)
    }
  }

  const onSubmit = async () => {
    if (busy || !editable) return
    setBusy('submit')
    try {
      let id = journalId
      // The rows of the lines as the server numbers them: after a save, the grid shows the saved lines in order.
      let rows = toInputs(lines).rows
      if (!id || dirty || (journal?.status !== 'DRAFT' && journal?.status !== 'REJECTED')) {
        const saved = await save()
        if (!saved) return
        id = saved.journalId
        rows = toInputs(lines).inputs.map((_, i) => i)
      }
      try {
        const output = await submitJournal(id as string, keyFor({ submit: id }))
        succeeded()
        setServer(null)
        message.success(
          output.status === 'POSTED'
            ? t('journal.posted', { journalNo: output.journalNo, glNo: output.glNo })
            : t('journal.waiting', { journalNo: output.journalNo }),
        )
        shownVersion.current = null
        // A read started before the submission (on opening the just-saved entry) would answer with the draft, and an
        // invalidation joins a read in flight while the entry has no data yet: cancel it, then read again.
        await queryClient.cancelQueries({ queryKey: ['fin', 'journal', id] })
        await queryClient.invalidateQueries({ queryKey: ['fin', 'journal', id] })
      } catch (error) {
        showRefusal(error, rows)
      }
    } finally {
      setBusy(null)
    }
  }

  const onDelete = async () => {
    if (!journalId || busy) return
    setBusy('delete')
    try {
      await runProcess(PROCESSES.delete, { journalId })
      message.success(t('journal.deleted'))
      navigate(JOURNALS_PATH)
    } catch (error) {
      showRefusal(error, [])
    } finally {
      setBusy(null)
    }
  }

  const copyLines = async () => {
    const header = COLUMNS.map((column) => t(`journal.column.${column}`))
    await navigator.clipboard.writeText(toTsv(lines, header))
    message.success(t('journal.copied'))
  }

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const ctrl = event.ctrlKey || event.metaKey
    if (ctrl && event.key.toLowerCase() === 's') {
      event.preventDefault()
      void onSave()
    } else if (ctrl && event.key === 'Enter') {
      event.preventDefault()
      void onSubmit()
    }
  }

  if (journalId && loaded.isLoading && !startedNew) return <Card loading />
  if (journalId && loaded.error) {
    return <Alert type="error" showIcon message={loaded.error instanceof ApiError ? loaded.error.display : String(loaded.error)} />
  }

  return (
    <div onKeyDown={onKeyDown} data-testid="journal-entry-page">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space align="center" wrap>
          <Link to={JOURNALS_PATH}>{t('journal.backToList')}</Link>
          <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">
            {journal?.journalNo ? t('journal.titleNumbered', { journalNo: journal.journalNo }) : t('journal.titleNew')}
          </Typography.Title>
          {journal && <StatusTag status={journal.status} />}
          {dirty && <Tag>{t('journal.unsaved')}</Tag>}
        </Space>

        {journal && <EntryFacts journal={journal} />}
        {journal && journal.status !== 'DRAFT' && journal.status !== 'POSTED' && dirty && (
          <Alert type="warning" showIcon message={t('journal.changeReturnsToDraft')} />
        )}

        <Card size="small">
          <Space wrap size="large" align="end">
            <Field id={`${formId}-posting`} label={t('journal.postingDate')}>
              <Input id={`${formId}-posting`} type="date" value={header.postingDate} disabled={!editable}
                onChange={(e) => changeHeader({ postingDate: e.target.value })} style={{ width: 170 }} />
            </Field>
            <Field id={`${formId}-document`} label={t('journal.documentDate')}>
              <Input id={`${formId}-document`} type="date" value={header.documentDate} disabled={!editable}
                onChange={(e) => changeHeader({ documentDate: e.target.value })} style={{ width: 170 }} />
            </Field>
            <Field id={`${formId}-description`} label={t('journal.description')} grow>
              <Input id={`${formId}-description`} value={header.description} disabled={!editable} maxLength={500}
                onChange={(e) => changeHeader({ description: e.target.value })} />
            </Field>
            <Field id={`${formId}-reverse`} label={t('journal.autoReverseDate')}>
              <Input id={`${formId}-reverse`} type="date" value={header.autoReverseDate} disabled={!editable}
                style={{ width: 170 }} onChange={(e) => changeHeader({ autoReverseDate: e.target.value })} />
            </Field>
            <Checkbox checked={header.adjusting} disabled={!editable}
              onChange={(e) => changeHeader({ adjusting: e.target.checked, adjustmentPeriod: e.target.checked && header.adjustmentPeriod })}>
              {t('journal.adjusting')}
            </Checkbox>
            <Checkbox checked={header.adjustmentPeriod} disabled={!editable || !header.adjusting}
              onChange={(e) => changeHeader({ adjustmentPeriod: e.target.checked })}>
              {t('journal.adjustmentPeriod')}
            </Checkbox>
          </Space>
        </Card>

        {server && server.general.length > 0 && (
          <Alert type="error" showIcon data-testid="journal-problems"
            message={t('journal.refused')}
            description={<ul style={{ margin: 0, paddingLeft: 18 }}>{server.general.map((text) => <li key={text}>{text}</li>)}</ul>} />
        )}

        <Card size="small">
          <JournalGrid
            lines={lines}
            onChange={changeLines}
            onUndo={() => step(undo)}
            onRedo={() => step(redo)}
            problems={problems}
            serverProblems={server?.cells}
            accounts={accountOptions}
            departments={[...(dimensions.data?.departments ?? [])]}
            locations={[...(dimensions.data?.locations ?? [])]}
            readOnly={!editable}
            onPasteDropped={(dropped) => message.warning(t('journal.pasteDropped', { count: dropped }))}
          />
        </Card>

        <Space wrap>
          {editable && (
            <>
              <Button onClick={() => void onSave()} loading={busy === 'save'} disabled={busy !== null} title="Ctrl+S">
                {t('journal.save')}
              </Button>
              <Button type="primary" onClick={() => void onSubmit()} loading={busy === 'submit'} disabled={busy !== null}
                title="Ctrl+Enter">
                {t('journal.submit')}
              </Button>
              <Button onClick={() => step(undo)} disabled={history.past.length === 0} title="Ctrl+Z">
                {t('journal.undo')}
              </Button>
              <Button onClick={() => step(redo)} disabled={history.future.length === 0} title="Ctrl+Y">
                {t('journal.redo')}
              </Button>
            </>
          )}
          <Button onClick={() => void copyLines()}>{t('journal.copyLines')}</Button>
          {editable && journal && !journal.journalNo && (
            <Button danger onClick={() => void onDelete()} loading={busy === 'delete'} disabled={busy !== null}>
              {t('journal.delete')}
            </Button>
          )}
        </Space>

        {journal && <EntryActions journal={journal} onDecided={() => setDecidedAt(Date.now())} />}
        {journal && loaded.data && (
          <AttachmentsCard journal={journal} attachments={loaded.data.attachments} editable={editable} />
        )}
      </Space>
    </div>
  )
}

/** A header field with its label above it. */
function Field({ id, label, grow, children }: { id: string; label: string; grow?: boolean; children: ReactNode }) {
  return (
    <div style={grow ? { flex: 1, minWidth: 320 } : undefined}>
      <label htmlFor={id} style={{ display: 'block' }}>
        {label}
      </label>
      {children}
    </div>
  )
}

/** Number, period, general ledger number, approval and exception of a saved entry. */
function EntryFacts({ journal }: { journal: Journal }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <Descriptions size="small" column={{ xs: 1, md: 3 }} bordered data-testid="journal-facts">
      <Descriptions.Item label={t('journal.source')}>{t(`journal.sources.${journal.source}`, journal.source)}</Descriptions.Item>
      <Descriptions.Item label={t('journal.preparer')}><UserName id={journal.preparer} /></Descriptions.Item>
      <Descriptions.Item label={t('journal.period')}>{journal.periodKey ?? '—'}</Descriptions.Item>
      <Descriptions.Item label={t('journal.totalDebit')}>{formatAmount(journal.totalDebit, { scale: 2 })}</Descriptions.Item>
      <Descriptions.Item label={t('journal.glNo')}>
        <span data-testid="journal-gl-no">{journal.glNo ?? '—'}</span>
      </Descriptions.Item>
      <Descriptions.Item label={t('journal.history')}>
        <Link to={paths.history(DATASETS.journal, journal.journalId)}>{t('journal.viewHistory')}</Link>
      </Descriptions.Item>
      {journal.exceptionBy && (
        <Descriptions.Item label={t('journal.exception')} span={3}>
          {t('journal.exceptionGranted', { by: journal.exceptionBy, reason: journal.exceptionReason ?? '' })}
        </Descriptions.Item>
      )}
      {journal.reversesJournalId && (
        <Descriptions.Item label={t('journal.reverses')}>
          <Link to={journalPath(journal.reversesJournalId)}>{t('journal.original')}</Link>
        </Descriptions.Item>
      )}
      {journal.reversedById && (
        <Descriptions.Item label={t('journal.reversedBy')}>
          <Link to={journalPath(journal.reversedById)}>{t('journal.reversal')}</Link>
        </Descriptions.Item>
      )}
      {journal.autoReverseDate && (
        <Descriptions.Item label={t('journal.autoReverseDate')}>{formatDate(journal.autoReverseDate)}</Descriptions.Item>
      )}
    </Descriptions>
  )
}

/** Approval, the control-account exception and reversal: each offered to those whose permissions allow it. */
function EntryActions({ journal, onDecided }: { journal: Journal; onDecided: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const { message } = App.useApp()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [reason, setReason] = useState('')
  const [reversalDate, setReversalDate] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['fin', 'journal', journal.journalId] })

  const run = async (action: () => Promise<void>) => {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }

  const approving = journal.status === 'SUBMITTED' && journal.approvalRequestId && can(PERMISSIONS.decide)
  const granting = (journal.status === 'DRAFT' || journal.status === 'REJECTED') && can(PERMISSIONS.exception)
  const reversing = journal.status === 'POSTED' && !journal.reversedById && can(PERMISSIONS.prepare)
  if (!approving && !granting && !reversing) return null

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {error && <Alert type="error" showIcon message={error} data-testid="action-error" />}
      {approving && (
        <Card size="small" title={t('journal.approval')}>
          <ApprovalPanel
            requestId={journal.approvalRequestId as string}
            onDecided={() => {
              onDecided()
              void refresh()
            }}
          />
        </Card>
      )}
      {granting && (
        <Card size="small" title={t('journal.grantException')}>
          <Space.Compact style={{ width: '100%' }}>
            <Input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500}
              placeholder={t('journal.exceptionReason')} aria-label={t('journal.exceptionReason')} />
            <Button disabled={busy || !reason.trim()} onClick={() => void run(async () => {
              await runProcess(PROCESSES.exception, { journalId: journal.journalId, reason: reason.trim() })
              message.success(t('journal.exceptionDone'))
              setReason('')
              await refresh()
            })}>
              {t('journal.grant')}
            </Button>
          </Space.Compact>
        </Card>
      )}
      {reversing && (
        <Card size="small" title={t('journal.reverse')}>
          <Space>
            <Input type="date" value={reversalDate} onChange={(e) => setReversalDate(e.target.value)}
              aria-label={t('journal.reversalDate')} style={{ width: 170 }} />
            <Button disabled={busy || !reversalDate} onClick={() => void run(async () => {
              const output = await runProcess<JournalOutput>(PROCESSES.reverse, {
                journalId: journal.journalId,
                postingDate: reversalDate,
              })
              message.success(t('journal.reversed', { journalNo: output.journalNo }))
              navigate(journalPath(output.journalId))
            })}>
              {t('journal.reverse')}
            </Button>
          </Space>
        </Card>
      )}
    </Space>
  )
}
