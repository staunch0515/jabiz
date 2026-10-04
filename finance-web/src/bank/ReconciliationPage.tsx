import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  ApprovalPanel,
  EXTENSION_NAMESPACE,
  formatAmount,
  formatDate,
  formatDateTime,
  paths,
  runProcess,
  useAuth,
  UserName,
} from '@jabiz/admin'
import { Alert, App, Button, Card, Descriptions, Popconfirm, Space, Table, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  DATASETS,
  downloadReport,
  loadReconciliation,
  PERMISSIONS,
  prepare,
  PROCESSES,
  QUERIES,
  save,
  type LoadedReconciliation,
  type RecRow,
} from './api'
import { MATCHING_PATH, RECONCILIATIONS_PATH } from './paths'
import { RecStatusTag } from './StatusTag'

const money = (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 })
const message = (e: unknown) => (e instanceof ApiError ? e.display : String(e))
/** The sections that are totals rather than items. */
const TOTALS = new Set(['STATEMENT_BALANCE', 'ADJUSTED_BANK_BALANCE', 'BOOK_BALANCE', 'DIFFERENCE'])

/**
 * A bank reconciliation (FIN-BK-007…009): the statement balance, the deposits in transit and outstanding payments
 * carried forward, the adjusted bank balance against the books, the lines not in the books and the difference, as
 * worked out now; prepared again while the books move, completed by its preparer at a difference of zero, signed off
 * by a reviewer here (the platform's approval), then issued once; the issued report is the archive's, reprinted
 * exactly. Each action is the server's process; the page offers what the user may do.
 */
export default function ReconciliationPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { reconciliationId } = useParams<{ reconciliationId: string }>()
  // The sign-off comes back through the platform's events a moment after the decision: look again until then.
  const loaded = useQuery({ queryKey: ['fin', 'bank', 'rec', reconciliationId],
    queryFn: () => loadReconciliation(reconciliationId as string),
    refetchInterval: (query) => (query.state.data?.rec.status === 'SUBMITTED' ? 2_000 : false) })

  if (loaded.isLoading) return <Card loading />
  if (loaded.error || !loaded.data) return <Alert type="error" showIcon message={message(loaded.error)} />
  const { rec } = loaded.data

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }} data-testid="rec-page">
      <Space align="center" wrap>
        <Link to={RECONCILIATIONS_PATH}>{t('bank.backToReconciliations')}</Link>
        <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">
          {t('bank.titleReconciliation', { bank: rec.bankCode, day: formatDate(rec.statementDate) })}
        </Typography.Title>
        <RecStatusTag status={rec.status} />
      </Space>
      <Descriptions size="small" bordered column={{ xs: 1, md: 3 }} data-testid="rec-facts">
        <Descriptions.Item label={t('bank.statementBalance')}>{money(rec.statementBalance)}</Descriptions.Item>
        <Descriptions.Item label={t('bank.depositsInTransit')}>{money(rec.depositsInTransit)}</Descriptions.Item>
        <Descriptions.Item label={t('bank.outstandingPayments')}>{money(rec.outstandingPayments)}</Descriptions.Item>
        <Descriptions.Item label={t('bank.adjustedBalance')}>
          <span data-testid="rec-adjusted">{money(rec.adjustedBalance)}</span>
        </Descriptions.Item>
        <Descriptions.Item label={t('bank.bookBalance')}><span data-testid="rec-book">{money(rec.bookBalance)}</span></Descriptions.Item>
        <Descriptions.Item label={t('bank.difference')}>
          <Typography.Text type={Number(rec.difference) === 0 ? 'success' : 'danger'} data-testid="rec-difference">
            {money(rec.difference)}
          </Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label={t('bank.preparedBy')}>{rec.preparedBy ? <UserName id={rec.preparedBy} /> : '—'}</Descriptions.Item>
        <Descriptions.Item label={t('bank.reviewedBy')}>{rec.reviewedBy ? <UserName id={rec.reviewedBy} /> : '—'}</Descriptions.Item>
        <Descriptions.Item label={t('bank.signedOff')}>{rec.signedOffTime ? formatDateTime(rec.signedOffTime) : '—'}</Descriptions.Item>
        <Descriptions.Item label={t('bank.matching')}>
          <Link to={`${MATCHING_PATH}?bank=${encodeURIComponent(rec.bankCode)}`}>{t('bank.openMatching')}</Link>
        </Descriptions.Item>
        <Descriptions.Item label={t('bank.history')}>
          <Link to={paths.history(DATASETS.reconciliation, rec.reconciliationId)}>{t('bank.viewHistory')}</Link>
        </Descriptions.Item>
      </Descriptions>
      <Actions loaded={loaded.data} />
      <RowsCard rows={loaded.data.rows} signedOff={rec.status === 'SIGNED_OFF'} />
    </Space>
  )
}

/** The rows as worked out now, by section; once signed off, the issued report is the record. */
function RowsCard({ rows, signedOff }: { rows: RecRow[]; signedOff: boolean }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const shown = rows.filter((r) => r.section !== 'PREPARED_BY' && r.section !== 'REVIEWED_BY')
  return (
    <Card size="small" title={t('bank.rows')}>
      {signedOff && <Alert type="info" showIcon style={{ marginBottom: 8 }} message={t('bank.rowsNow')} />}
      <Table<RecRow>
        size="small"
        rowKey={(r) => String(r.seq)}
        data-testid="rec-rows"
        pagination={false}
        dataSource={shown}
        columns={[
          { title: t('bank.section'), dataIndex: 'section', render: (v: string) => (TOTALS.has(v)
            ? <Typography.Text strong>{t(`bank.sections.${v}`, v)}</Typography.Text> : t(`bank.sections.${v}`, v)) },
          { title: t('bank.date'), dataIndex: 'itemDate', render: (v: string | null, r) => (v && !TOTALS.has(r.section)
            ? formatDate(v) : '') },
          { title: t('bank.reference'), dataIndex: 'reference' },
          { title: t('bank.description'), dataIndex: 'description', ellipsis: true,
            render: (v: string | null, r) => (TOTALS.has(r.section) ? '' : v) },
          { title: t('bank.amount'), dataIndex: 'amount', align: 'right', render: (v: unknown, r) => (TOTALS.has(r.section)
            ? <Typography.Text strong data-testid={`row-${r.section}`}>{money(v)}</Typography.Text> : money(v)) },
        ]}
      />
    </Card>
  )
}

function Actions({ loaded }: { loaded: LoadedReconciliation }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can, userId } = useAuth()
  const { message: toast } = App.useApp()
  const queryClient = useQueryClient()
  const { rec } = loaded
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['fin', 'bank'] })

  const act = async (name: string, work: () => Promise<unknown>, done: string) => {
    setBusy(name)
    setError(null)
    try {
      await work()
      toast.success(done)
      await refresh()
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(null)
    }
  }

  const download = async (format: 'pdf' | 'csv') => {
    setError(null)
    try {
      const { blob, fileName } = await downloadReport(rec.reportRunId as string, format)
      save(blob, fileName ?? `reconciliation-${rec.bankCode}-${rec.statementDate}.${format}`)
    } catch (e) {
      setError(message(e))
    }
  }

  const reconciling = can(PERMISSIONS.reconcile)
  // Only a reviewer of reconciliations other than its preparer decides (FIN-BK-008); the server says so in the end.
  const preparer = rec.preparedBy != null && rec.preparedBy === userId
  const deciding = rec.status === 'SUBMITTED' && rec.approvalRequestId && can(PERMISSIONS.decide)
    && can(PERMISSIONS.review) && !preparer
  const input = { reconciliationId: rec.reconciliationId }

  return (
    <Card size="small" title={t('bank.actionsTitle')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} data-testid="rec-error" />}
        {rec.status === 'SUBMITTED' && <Alert type="info" showIcon message={t('bank.waitingForReview')} />}
        {deciding && <ApprovalPanel requestId={rec.approvalRequestId as string} onDecided={() => void refresh()} />}
        <Space wrap>
          {rec.status === 'PREPARED' && reconciling && (
            <>
              <Button disabled={busy !== null} loading={busy === 'prepare'} data-testid="rec-prepare"
                onClick={() => void act('prepare', () => prepare(rec.bankCode, rec.statementDate), t('bank.prepared'))}>
                {t('bank.prepareAgain')}
              </Button>
              <Popconfirm title={t('bank.completeConfirm')} okText={t('bank.complete')}
                okButtonProps={{ ...{ 'data-testid': 'rec-complete-confirm' } }}
                onConfirm={() => void act('complete', () => runProcess(PROCESSES.complete, input), t('bank.completed'))}>
                <Button type="primary" disabled={busy !== null} loading={busy === 'complete'} data-testid="rec-complete">
                  {t('bank.complete')}
                </Button>
              </Popconfirm>
            </>
          )}
          {rec.status === 'SUBMITTED' && reconciling && preparer && (
            <Button disabled={busy !== null} loading={busy === 'withdraw'} data-testid="rec-withdraw"
              onClick={() => void act('withdraw', () => runProcess(PROCESSES.withdraw, input), t('bank.withdrawn'))}>
              {t('bank.withdraw')}
            </Button>
          )}
          {rec.status === 'SIGNED_OFF' && !rec.reportRunId && reconciling && (
            <Button type="primary" disabled={busy !== null} loading={busy === 'issue'} data-testid="rec-issue"
              onClick={() => void act('issue', () => runProcess(PROCESSES.issue, input), t('bank.issued'))}>
              {t('bank.issue')}
            </Button>
          )}
          {rec.reportRunId && can(PERMISSIONS.archive) && (
            <>
              <Button onClick={() => void download('pdf')} data-testid="rec-pdf">{t('bank.downloadPdf')}</Button>
              <Button onClick={() => void download('csv')} data-testid="rec-csv">{t('bank.downloadCsv')}</Button>
              <Link to={paths.reportArchive(QUERIES.reconciliation)}>{t('bank.archive')}</Link>
            </>
          )}
        </Space>
        {rec.reportRunId && (
          <Typography.Text type="secondary" data-testid="rec-report">
            {t('bank.reportIssued', { hash: (rec.reportHash ?? '').slice(0, 12) })}
          </Typography.Text>
        )}
      </Space>
    </Card>
  )
}
