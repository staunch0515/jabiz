import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  EXTENSION_NAMESPACE,
  formatAmount,
  formatDate,
  formatDateTime,
  useAuth,
  UserName,
} from '@jabiz/admin'
import { Alert, Button, Card, Input, Modal, Space, Table, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { loadRuns, nextMonth, PERMISSIONS, reverseRun, runDepreciation, type Run, type RunOutput } from './api'
import { RunStatusTag } from './StatusTag'

const money = (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 })
const message = (e: unknown) => (e instanceof ApiError ? e.display : String(e))
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

/**
 * The monthly depreciation runs (FIN-FA-005): the next month run, one entry for all assets in service, and the runs
 * so far; the latest run of an open month may be taken back and the month run again (F6 plan decision D7).
 */
export default function DepreciationPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const client = useQueryClient()
  const runs = useQuery({ queryKey: ['fin', 'fa', 'runs'], queryFn: loadRuns })
  const [reversing, setReversing] = useState<Run | null>(null)
  // Kept here: the run card starts afresh once the next month moves on.
  const [result, setResult] = useState<RunOutput | null>(null)
  const latest = runs.data?.find((r) => r.status === 'POSTED')
  const refresh = () => client.invalidateQueries({ queryKey: ['fin', 'fa'] })

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{t('fa.depreciationTitle')}</Typography.Title>
      {result && (
        <Alert type={result.posted ? 'success' : 'info'} showIcon data-testid="run-result" closable
          onClose={() => setResult(null)}
          message={result.posted
            ? t('fa.runPosted', { runNo: result.runNo, total: money(result.total), count: result.assetCount })
            : t('fa.runAlready', { runNo: result.runNo })} />
      )}
      {can(PERMISSIONS.run) && runs.data && <RunCard key={nextMonth(runs.data) ?? ''} next={nextMonth(runs.data)}
        onStart={() => setResult(null)} onDone={(output) => {
          setResult(output)
          void refresh()
        }} />}
      <Table<Run>
        data-testid="run-table"
        size="small"
        rowKey="runId"
        loading={runs.isLoading}
        dataSource={runs.data ?? []}
        pagination={{ pageSize: 24, showSizeChanger: false }}
        locale={{ emptyText: runs.error ? message(runs.error) : t('fa.noRuns') }}
        columns={[
          { title: t('fa.month'), dataIndex: 'periodKey' },
          { title: t('fa.runNo'), dataIndex: 'runNo' },
          { title: t('fa.postingDate'), dataIndex: 'postingDate', render: (v: string) => formatDate(v) },
          { title: t('fa.total'), dataIndex: 'total', align: 'right', render: money },
          { title: t('fa.assetCount'), dataIndex: 'assetCount', align: 'right' },
          { title: t('fa.status'), dataIndex: 'status', render: (v: Run['status']) => <RunStatusTag status={v} /> },
          { title: t('fa.runBy'), dataIndex: 'actor', render: (value?: string | null) => <UserName id={value} /> },
          { title: t('fa.runTime'), dataIndex: 'runTime', render: (v: string) => formatDateTime(v) },
          { title: t('fa.reason'), dataIndex: 'reason' },
          {
            title: '', key: 'actions',
            render: (_: unknown, row) => can(PERMISSIONS.run) && row.runId === latest?.runId
              ? <Button size="small" danger onClick={() => setReversing(row)} data-testid="reverse">{t('fa.reverse')}</Button>
              : null,
          },
        ]}
      />
      <ReverseDialog key={reversing?.runId ?? 'none'} run={reversing} onClose={() => setReversing(null)}
        onDone={() => {
          setResult(null)
          void refresh()
        }} />
    </Space>
  )
}

/**
 * A month run: the next one by default (remounted when it changes), refused by the server when out of order or
 * closed.
 */
function RunCard({ next, onStart, onDone }: {
  next: string | null
  onStart: () => void
  onDone: (output: RunOutput) => void
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [month, setMonth] = useState(next ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const go = async () => {
    setBusy(true)
    setError(null)
    onStart()
    try {
      onDone(await runDepreciation(month, crypto.randomUUID()))
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card size="small" title={t('fa.runTitle')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} data-testid="run-error" />}
        <Space wrap>
          <Input aria-label={t('fa.month')} placeholder="2026-01" style={{ width: 140 }} value={month}
            onChange={(e) => setMonth(e.target.value.trim())} data-testid="run-month" />
          <Button type="primary" disabled={!MONTH.test(month) || busy} loading={busy} onClick={() => void go()}
            data-testid="run">{t('fa.run')}</Button>
        </Space>
      </Space>
    </Card>
  )
}

function ReverseDialog({ run, onClose, onDone }: { run: Run | null; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const go = async () => {
    if (!run) return
    setBusy(true)
    setError(null)
    try {
      await reverseRun(run.periodKey, reason)
      onDone()
      onClose()
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal open={run !== null} title={t('fa.reverseTitle', { runNo: run?.runNo ?? '' })} onCancel={onClose}
      onOk={() => void go()} okText={t('fa.reverse')} confirmLoading={busy}
      okButtonProps={{ danger: true, disabled: !reason.trim(), ...{ 'data-testid': 'reverse-confirm' } }}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} data-testid="reverse-error" />}
        <Typography.Text type="secondary">{t('fa.reverseHint')}</Typography.Text>
        <Input.TextArea aria-label={t('fa.reason')} rows={2} maxLength={500} value={reason}
          onChange={(e) => setReason(e.target.value)} data-testid="reverse-reason" />
      </Space>
    </Modal>
  )
}
