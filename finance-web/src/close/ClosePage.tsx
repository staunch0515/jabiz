import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  EXTENSION_NAMESPACE,
  formatAmount,
  formatDate,
  formatDateTime,
  paths,
  useAuth,
  UserName,
} from '@jabiz/admin'
import { Alert, Button, Card, Input, Progress, Select, Space, Table, Tag, Typography } from 'antd'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { closePeriod, closeYear, completeTask, currentPeriod, loadArtifacts, loadExceptions, loadOverview,
  loadPeriods, loadReopens, PERMISSIONS, QUERIES, requestReopen, runChecks, softClose, startClose,
  withdrawReopen, type Artifact, type Exception, type OverviewRow, type Period, type Reopen } from './api'

const money = (value: unknown) => (value === null || value === undefined ? ''
  : formatAmount(value as number | string, { scale: 2 }))
const date = (value: unknown) => (value ? formatDate(value as string) : '')
const dateTime = (value: unknown) => (value ? formatDateTime(value as string) : '')

/** A refusal's messages, one per violation: a refused close names each item that fails (FIN-PC-004). */
const messages = (e: unknown): string[] => {
  if (e instanceof ApiError) return e.violations.length > 0 ? e.violations.map((v) => v.message) : [e.message]
  return [String(e)]
}

const COLORS: Record<string, string> = {
  PASSED: 'success', DONE: 'success', SIGNED_OFF: 'success', COMPLETE: 'success', APPROVED: 'success',
  FAILED: 'error', MISSING: 'error', REJECTED: 'error',
  OPEN: 'processing', IN_PROGRESS: 'processing', PENDING: 'processing', PREPARED: 'processing',
  SUBMITTED: 'processing', SOFT_CLOSED: 'warning', CLOSED: 'default', NOT_STARTED: 'default',
}

/**
 * The close workspace (FIN-UI-006, FIN-PC-009; F8 plan decision D9): a period's progress, its checklist with the open
 * tasks first, owners and due dates, the subledgers' states, the bank reconciliations and the open exceptions; the
 * start, the checks, the manual tasks, the soft close and the close in one action each; the close artifacts with the
 * issued trial balances, the reopening requests, and the year close of an adjustment period. Every action is the
 * server's to allow; the page only leaves out what the user's permissions cannot do.
 */
export default function ClosePage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const client = useQueryClient()
  const [search, setSearch] = useSearchParams()
  const periods = useQuery({ queryKey: ['fin', 'close', 'periods'], queryFn: loadPeriods })
  const periodKey = search.get('period') ?? (periods.data ? currentPeriod(periods.data) : null)
  const period = periods.data?.find((p) => p.periodKey === periodKey)
  const [notice, setNotice] = useState<string | null>(null)
  const [errors, setErrors] = useState<string[]>([])
  const [busy, setBusy] = useState(false)

  // The period shown stays in the address: after a close the default would move on to the next open period.
  useEffect(() => {
    if (!search.get('period') && periodKey) setSearch({ period: periodKey }, { replace: true })
  }, [search, periodKey, setSearch])

  const act = async (action: () => Promise<string | null>) => {
    setBusy(true)
    setErrors([])
    setNotice(null)
    try {
      setNotice(await action())
    } catch (e) {
      setErrors(messages(e))
    } finally {
      setBusy(false)
      await client.invalidateQueries({ queryKey: ['fin', 'close'] })
    }
  }

  const choose = (key: string) => {
    setErrors([])
    setNotice(null)
    setSearch({ period: key })
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space wrap align="center">
        <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{t('close.title')}</Typography.Title>
        <Select<string> aria-label={t('close.period')} style={{ width: 160 }} value={periodKey ?? undefined}
          loading={periods.isLoading} onChange={choose} data-testid="period-select" showSearch
          options={(periods.data ?? []).map((p) => ({ value: p.periodKey, label: p.periodKey }))} />
        {period && <StatusTag status={period.status} testId="period-status" />}
      </Space>
      {periods.error && <Alert type="error" showIcon message={messages(periods.error).join(' ')} />}
      {notice && <Alert type="success" showIcon closable onClose={() => setNotice(null)} message={notice}
        data-testid="close-notice" />}
      {errors.length > 0 && (
        <Alert type="error" showIcon closable onClose={() => setErrors([])} data-testid="close-error"
          message={t('close.refused')}
          description={<ul style={{ margin: 0, paddingLeft: 20 }}>{errors.map((m) => <li key={m}>{m}</li>)}</ul>} />
      )}
      {period && <Workspace key={period.periodKey} period={period} busy={busy} act={act} />}
    </Space>
  )
}

function Workspace({ period, busy, act }: {
  period: Period
  busy: boolean
  act: (action: () => Promise<string | null>) => Promise<void>
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const key = period.periodKey
  const overview = useQuery({ queryKey: ['fin', 'close', 'overview', key], queryFn: () => loadOverview(key),
    enabled: can(PERMISSIONS.read) })
  const rows = overview.data ?? []
  const progress = rows.find((r) => r.section === 'PROGRESS')
  const tasks = rows.filter((r) => r.section === 'TASK')
  const closed = period.status === 'CLOSED'
  const [completing, setCompleting] = useState<OverviewRow | null>(null)

  const year = Number(period.fiscalYear)
  const actions: ReactNode[] = []
  if (!period.adjustment && !closed) {
    if (can(PERMISSIONS.task)) {
      actions.push(
        <Button key="start" disabled={busy} data-testid="close-start"
          onClick={() => void act(async () => { await startClose(key); return t('close.started', { period: key }) })}>
          {t('close.start')}
        </Button>,
        <Button key="check" disabled={busy} data-testid="close-check"
          onClick={() => void act(async () => { await runChecks(key); return t('close.checked', { period: key }) })}>
          {t('close.check')}
        </Button>,
      )
    }
    if (can(PERMISSIONS.close)) {
      if (period.status === 'OPEN') {
        actions.push(
          <Button key="soft" disabled={busy} data-testid="close-soft"
            onClick={() => void act(async () => { await softClose(key); return t('close.softClosed', { period: key }) })}>
            {t('close.softClose')}
          </Button>,
        )
      }
      actions.push(
        <Button key="close" type="primary" disabled={busy} data-testid="close-close"
          onClick={() => void act(async () => {
            const output = await closePeriod(key, crypto.randomUUID())
            return t('close.closed', { period: key, seq: output.seq })
          })}>
          {t('close.close')}
        </Button>,
      )
    }
  }
  if (period.adjustment && !closed && can(PERMISSIONS.close)) {
    actions.push(
      <Button key="year" type="primary" disabled={busy} data-testid="close-year"
        onClick={() => void act(async () => {
          const output = await closeYear(year, crypto.randomUUID())
          return t('close.yearClosed', { year, journalNo: output.journalNo ?? '-', netIncome: money(output.netIncome) })
        })}>
        {t('close.closeYear', { year })}
      </Button>,
    )
  }

  return (
    <>
      <Card size="small" data-testid="close-progress">
        <Space direction="vertical" style={{ width: '100%' }}>
          <Space wrap>
            {period.adjustment ? null : progress && Number(progress.total) > 0
              ? <><Typography.Text strong>{progress.name}</Typography.Text><StatusTag status={progress.status} /></>
              : <Typography.Text strong>{t('close.notStarted')}</Typography.Text>}
            {period.adjustment && <Typography.Text type="secondary">{t('close.adjustmentHint')}</Typography.Text>}
          </Space>
          {progress && Number(progress.total) > 0 && (
            <Progress percent={Math.round((Number(progress.done) / Number(progress.total)) * 100)} size="small" />
          )}
          {actions.length > 0 && <Space wrap>{actions}</Space>}
        </Space>
      </Card>
      {overview.error && <Alert type="error" showIcon message={messages(overview.error).join(' ')} />}
      {completing && (
        <CompleteCard key={completing.code} task={completing} periodKey={key} busy={busy}
          onCancel={() => setCompleting(null)}
          onComplete={(note, file) => act(async () => {
            await completeTask(completing.taskId as string, note, file)
            setCompleting(null)
            return t('close.completed', { code: completing.code })
          })} />
      )}
      {!period.adjustment && (
        <Card size="small" title={t('close.checklist')}>
          <Table<OverviewRow>
            data-testid="task-table"
            size="small"
            rowKey="code"
            loading={overview.isLoading}
            dataSource={tasks}
            pagination={false}
            locale={{ emptyText: t('close.noTasks') }}
            columns={[
              { title: t('close.code'), dataIndex: 'code' },
              { title: t('close.name'), dataIndex: 'name' },
              { title: t('close.kind'), dataIndex: 'kind', render: (v: string) => t(`close.kinds.${v}`, v) },
              { title: t('close.status'), dataIndex: 'status', render: (v: string) => <StatusTag status={v} /> },
              { title: t('close.required'), dataIndex: 'required', render: (v: boolean) => (v ? t('close.yes') : '') },
              { title: t('close.owner'), dataIndex: 'owner' },
              { title: t('close.due'), dataIndex: 'dueDate', render: date },
              { title: t('close.doneBy'), dataIndex: 'doneBy', render: (value?: string | null) => <UserName id={value} /> },
              { title: t('close.doneAt'), dataIndex: 'doneAt', render: dateTime },
              { title: t('close.detail'), dataIndex: 'detail', ellipsis: true },
              {
                title: '',
                key: 'actions',
                render: (_: unknown, row) => (row.kind === 'MANUAL' && row.status === 'OPEN' && !closed && row.taskId
                  && row.owner && can(row.owner) && can(PERMISSIONS.task)
                  ? <Button size="small" disabled={busy} onClick={() => setCompleting(row)}
                      aria-label={`${t('close.complete')} ${row.code}`}
                      data-testid={`complete-${row.code}`}>{t('close.complete')}</Button>
                  : null),
              },
            ]}
          />
        </Card>
      )}
      <Space align="start" wrap style={{ width: '100%' }}>
        <Card size="small" title={t('close.subledgers')}>
          <Table<OverviewRow>
            data-testid="subledger-table"
            size="small"
            rowKey="code"
            dataSource={rows.filter((r) => r.section === 'SUBLEDGER')}
            pagination={false}
            columns={[
              { title: t('close.ledger'), dataIndex: 'code', render: (v: string) => t(`close.ledgers.${v}`, v) },
              { title: t('close.status'), dataIndex: 'status', render: (v: string) => <StatusTag status={v} /> },
            ]}
          />
        </Card>
        {!period.adjustment && (
          <Card size="small" title={t('close.reconciliations')}>
            <Table<OverviewRow>
              data-testid="reconciliation-table"
              size="small"
              rowKey="code"
              dataSource={rows.filter((r) => r.section === 'RECONCILIATION')}
              pagination={false}
              locale={{ emptyText: t('close.noBankAccounts') }}
              columns={[
                { title: t('close.bankAccount'), dataIndex: 'code' },
                { title: t('close.glAccount'), dataIndex: 'name' },
                { title: t('close.status'), dataIndex: 'status', render: (v: string) => <StatusTag status={v} /> },
                { title: t('close.preparedBy'), dataIndex: 'owner' },
                { title: t('close.reviewedBy'), dataIndex: 'doneBy', render: (value?: string | null) => <UserName id={value} /> },
                { title: t('close.detail'), dataIndex: 'detail' },
              ]}
            />
          </Card>
        )}
      </Space>
      {!period.adjustment && PERMISSIONS.exceptions.every((p) => can(p)) && <ExceptionsCard periodKey={key} />}
      <ArtifactsCard periodKey={key} />
      {closed && <ReopenCard period={period} busy={busy} act={act} />}
    </>
  )
}

function StatusTag({ status, testId }: { status: string; testId?: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return <Tag color={COLORS[status] ?? 'default'} data-testid={testId}>{t(`close.statuses.${status}`, status)}</Tag>
}

/** A manual task done: a note and, if any, its evidence (PDF or image, file policy fin.close.evidence). */
function CompleteCard({ task, periodKey, busy, onCancel, onComplete }: {
  task: OverviewRow
  periodKey: string
  busy: boolean
  onCancel: () => void
  onComplete: (note: string, file: File | null) => Promise<void>
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [note, setNote] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const picker = useRef<HTMLInputElement>(null)
  return (
    <Card size="small" title={t('close.completeTitle', { code: task.code, period: periodKey })}
      data-testid="complete-card">
      <Space direction="vertical" style={{ width: '100%' }}>
        <Typography.Text>{task.name}</Typography.Text>
        <Input.TextArea value={note} maxLength={1000} rows={2} onChange={(e) => setNote(e.target.value)}
          placeholder={t('close.note')} aria-label={t('close.note')} data-testid="complete-note" />
        <Space wrap>
          <Button onClick={() => picker.current?.click()}>{t('close.evidence')}</Button>
          {file && <Typography.Text data-testid="complete-file">{file.name}</Typography.Text>}
          <input ref={picker} type="file" hidden accept=".pdf,.png,.jpg,.jpeg" aria-label={t('close.evidence')}
            data-testid="complete-evidence" onChange={(e) => {
              setFile(e.target.files?.[0] ?? null)
              e.target.value = ''
            }} />
        </Space>
        <Space>
          <Button type="primary" disabled={busy} loading={busy} data-testid="complete-submit"
            onClick={() => void onComplete(note.trim(), file)}>{t('close.complete')}</Button>
          <Button onClick={onCancel}>{t('close.cancel')}</Button>
        </Space>
      </Space>
    </Card>
  )
}

/** What keeps the automatic checks from passing, each with the check it fails (template finance.close.exceptions). */
function ExceptionsCard({ periodKey }: { periodKey: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const exceptions = useQuery({ queryKey: ['fin', 'close', 'exceptions', periodKey],
    queryFn: () => loadExceptions(periodKey) })
  return (
    <Card size="small" title={t('close.exceptions')}>
      <Table<Exception>
        data-testid="exception-table"
        size="small"
        rowKey={(row) => `${row.checkCode}|${row.itemId}`}
        loading={exceptions.isLoading}
        dataSource={exceptions.data ?? []}
        pagination={{ pageSize: 20, showSizeChanger: false, hideOnSinglePage: true }}
        locale={{ emptyText: exceptions.error ? messages(exceptions.error).join(' ') : t('close.noExceptions') }}
        columns={[
          { title: t('close.checkCode'), dataIndex: 'checkCode' },
          { title: t('close.reference'), dataIndex: 'reference' },
          { title: t('close.description'), dataIndex: 'description' },
          { title: t('close.date'), dataIndex: 'itemDate', render: date },
          { title: t('close.amount'), dataIndex: 'amount', align: 'right', render: money },
        ]}
      />
    </Card>
  )
}

/** The period's close artifacts, latest first, each superseded by the next close after a reopening (FIN-PC-005, 006). */
function ArtifactsCard({ periodKey }: { periodKey: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const artifacts = useQuery({ queryKey: ['fin', 'close', 'artifacts', periodKey],
    queryFn: () => loadArtifacts(periodKey) })
  const seqOf = (id?: string | null) => artifacts.data?.find((a) => a.artifactId === id)?.seq
  return (
    <Card size="small" title={t('close.artifacts')}
      extra={<Link to={paths.reportArchive(QUERIES.trialBalance)}>{t('close.issuedTrialBalances')}</Link>}>
      <Table<Artifact>
        data-testid="artifact-table"
        size="small"
        rowKey="artifactId"
        loading={artifacts.isLoading}
        dataSource={artifacts.data ?? []}
        pagination={false}
        locale={{ emptyText: artifacts.error ? messages(artifacts.error).join(' ') : t('close.noArtifacts') }}
        columns={[
          { title: t('close.seq'), dataIndex: 'seq' },
          { title: t('close.closedBy'), dataIndex: 'closedBy', render: (value?: string | null) => <UserName id={value} /> },
          { title: t('close.closedAt'), dataIndex: 'closedAt', render: dateTime },
          { title: t('close.totalDebit'), dataIndex: 'totalDebit', align: 'right', render: money },
          { title: t('close.totalCredit'), dataIndex: 'totalCredit', align: 'right', render: money },
          {
            title: t('close.contentHash'),
            dataIndex: 'contentHash',
            render: (v: string) => <Typography.Text copyable={{ text: v }}>{v.slice(0, 12)}…</Typography.Text>,
          },
          {
            title: t('close.supersession'),
            key: 'supersession',
            render: (_: unknown, row) => (row.supersededBy
              ? <Tag>{t('close.supersededBy', { seq: seqOf(row.supersededBy) ?? '?' })}</Tag>
              : row.supersedes ? t('close.supersedes', { seq: seqOf(row.supersedes) ?? '?' }) : ''),
          },
        ]}
      />
    </Card>
  )
}

/** A closed period goes back to open only through an approved request (FIN-PC-006). */
function ReopenCard({ period, busy, act }: {
  period: Period
  busy: boolean
  act: (action: () => Promise<string | null>) => Promise<void>
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can, userId } = useAuth()
  const key = period.periodKey
  const reopens = useQuery({ queryKey: ['fin', 'close', 'reopens', key], queryFn: () => loadReopens(key) })
  const [reason, setReason] = useState('')
  const pending = (reopens.data ?? []).some((r) => r.status === 'PENDING')
  return (
    <Card size="small" title={t('close.reopenTitle')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {can(PERMISSIONS.reopen) && !pending && (
          <Space.Compact style={{ width: '100%' }}>
            <Input value={reason} maxLength={1000} onChange={(e) => setReason(e.target.value)}
              placeholder={t('close.reason')} aria-label={t('close.reason')} data-testid="reopen-reason" />
            <Button disabled={busy || !reason.trim()} data-testid="reopen-request"
              onClick={() => void act(async () => {
                await requestReopen(key, reason.trim())
                setReason('')
                return t('close.reopenRequested', { period: key })
              })}>{t('close.requestReopen')}</Button>
          </Space.Compact>
        )}
        <Table<Reopen>
          data-testid="reopen-table"
          size="small"
          rowKey="reopenId"
          loading={reopens.isLoading}
          dataSource={reopens.data ?? []}
          pagination={false}
          locale={{ emptyText: reopens.error ? messages(reopens.error).join(' ') : t('close.noReopens') }}
          columns={[
            { title: t('close.requestedAt'), dataIndex: 'requestedAt', render: dateTime },
            { title: t('close.requestedBy'), dataIndex: 'requestedBy', render: (value?: string | null) => <UserName id={value} /> },
            { title: t('close.reason'), dataIndex: 'reason' },
            { title: t('close.status'), dataIndex: 'status', render: (v: string) => <StatusTag status={v} /> },
            { title: t('close.decidedBy'), dataIndex: 'decidedBy', render: (value?: string | null) => <UserName id={value} /> },
            {
              title: '',
              key: 'actions',
              // Only the requester withdraws a request.
              render: (_: unknown, row) => (row.status === 'PENDING' && row.requestedBy === userId
                ? <Button size="small" disabled={busy} data-testid="reopen-withdraw"
                    aria-label={`${t('close.withdraw')} ${dateTime(row.requestedAt)}`}
                    onClick={() => void act(async () => {
                      await withdrawReopen(row.reopenId)
                      return t('close.reopenWithdrawn', { period: key })
                    })}>{t('close.withdraw')}</Button>
                : null),
            },
          ]}
        />
      </Space>
    </Card>
  )
}
