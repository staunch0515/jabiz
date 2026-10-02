import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, formatAmount, formatDate, formatDateTime, useAuth } from '@jabiz/admin'
import { Alert, Button, Card, Checkbox, Input, Space, Table, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { loadRuns, nextMonth, PERMISSIONS, revalue, simulate, type Run, type RunOutput, type SimulatedLine,
  type Simulation } from './api'

const money = (value: unknown) => (value === null || value === undefined ? ''
  : formatAmount(value as number | string, { scale: 2 }))
const rate = (value: unknown) => (value === null || value === undefined ? '' : String(Number(value)))
const message = (e: unknown) => (e instanceof ApiError ? e.display : String(e))
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

/**
 * Period-end revaluation of foreign currency items (FIN-FX-005; F7 plan decisions D6, D7): a month's run, posted on
 * its last day and reversed on the next, once per month; the runs so far; and a simulation of a month, with today's
 * items and rates or as recorded when its run was made, beside the run's lines.
 */
export default function RevaluationPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const client = useQueryClient()
  const runs = useQuery({ queryKey: ['fin', 'fx', 'runs'], queryFn: loadRuns })
  const [result, setResult] = useState<RunOutput | null>(null)

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{t('fx.title')}</Typography.Title>
      {result && (
        <Alert type={result.created ? 'success' : 'info'} showIcon closable data-testid="run-result"
          onClose={() => setResult(null)}
          message={result.created
            ? t('fx.runPosted', { runNo: result.runNo, total: money(result.total), count: result.lineCount,
              reversal: formatDate(result.reversalDate) })
            : t('fx.runAlready', { runNo: result.runNo })} />
      )}
      {can(PERMISSIONS.run) && runs.data && (
        <Space align="start" wrap style={{ width: '100%' }}>
          <RunCard key={nextMonth(runs.data) ?? ''} next={nextMonth(runs.data)} onStart={() => setResult(null)}
            onDone={(output) => {
              setResult(output)
              void client.invalidateQueries({ queryKey: ['fin', 'fx'] })
            }} />
        </Space>
      )}
      <Table<Run>
        data-testid="run-table"
        size="small"
        rowKey="runId"
        loading={runs.isLoading}
        dataSource={runs.data ?? []}
        pagination={{ pageSize: 24, showSizeChanger: false }}
        locale={{ emptyText: runs.error ? message(runs.error) : t('fx.noRuns') }}
        columns={[
          { title: t('fx.month'), dataIndex: 'periodKey' },
          { title: t('fx.runNo'), dataIndex: 'runNo' },
          { title: t('fx.revaluedOn'), dataIndex: 'revaluationDate', render: (v: string) => formatDate(v) },
          { title: t('fx.reversedOn'), dataIndex: 'reversalDate', render: (v: string) => formatDate(v) },
          { title: t('fx.rateType'), dataIndex: 'rateType' },
          { title: t('fx.total'), dataIndex: 'total', align: 'right', render: money },
          { title: t('fx.lineCount'), dataIndex: 'lineCount', align: 'right' },
          { title: t('fx.runBy'), dataIndex: 'actor' },
          { title: t('fx.runTime'), dataIndex: 'runTime', render: (v: string) => formatDateTime(v) },
        ]}
      />
      {/* Mounted once the runs are in, and kept: a run made meanwhile leaves the simulation on screen. */}
      {can(PERMISSIONS.run) && runs.data && <SimulationCard initial={runs.data[0]?.periodKey ?? ''} />}
    </Space>
  )
}

/** A month's run: the month after the latest by default; the server refuses a closed month or a missing rate. */
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
      onDone(await revalue(month, crypto.randomUUID()))
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card size="small" title={t('fx.runTitle')}>
      <Space direction="vertical">
        {error && <Alert type="error" showIcon message={error} data-testid="run-error" />}
        <Space wrap>
          <Input aria-label={t('fx.month')} placeholder="2026-01" style={{ width: 140 }} value={month}
            onChange={(e) => setMonth(e.target.value.trim())} data-testid="run-month" />
          <Button type="primary" disabled={!MONTH.test(month) || busy} loading={busy} onClick={() => void go()}
            data-testid="run">{t('fx.run')}</Button>
        </Space>
        <Typography.Text type="secondary">{t('fx.runHint')}</Typography.Text>
      </Space>
    </Card>
  )
}

/** A month computed again: what changed since its run (a corrected rate), or the run itself as recorded. */
function SimulationCard({ initial }: { initial: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [month, setMonth] = useState(initial)
  const [asRecorded, setAsRecorded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [simulation, setSimulation] = useState<Simulation | null>(null)

  const go = async () => {
    setBusy(true)
    setError(null)
    try {
      setSimulation(await simulate(month, asRecorded))
    } catch (e) {
      setSimulation(null)
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card size="small" title={t('fx.simulateTitle')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} data-testid="simulate-error" />}
        <Space wrap>
          <Input aria-label={t('fx.month')} placeholder="2026-01" style={{ width: 140 }} value={month}
            onChange={(e) => setMonth(e.target.value.trim())} data-testid="simulate-month" />
          <Checkbox checked={asRecorded} onChange={(e) => setAsRecorded(e.target.checked)}
            data-testid="simulate-recorded">{t('fx.asRecorded')}</Checkbox>
          <Button disabled={!MONTH.test(month) || busy} loading={busy} onClick={() => void go()}
            data-testid="simulate">{t('fx.simulate')}</Button>
        </Space>
        {simulation && (
          <>
            <Typography.Text data-testid="simulate-summary">
              {simulation.runNo
                ? t('fx.simulated', { runNo: simulation.runNo, total: money(simulation.total),
                  original: money(simulation.originalTotal), change: money(simulation.change) })
                : t('fx.simulatedNoRun', { total: money(simulation.total) })}
            </Typography.Text>
            <Table<SimulatedLine>
              data-testid="simulate-table"
              size="small"
              rowKey={(row) => `${row.kind}|${row.documentId}`}
              dataSource={simulation.lines}
              pagination={{ pageSize: 50, showSizeChanger: false }}
              locale={{ emptyText: t('fx.noItems') }}
              columns={[
                { title: t('fx.kind'), dataIndex: 'kind', render: (v: string) => t(`fx.kinds.${v}`, v) },
                { title: t('fx.document'), dataIndex: 'documentNo' },
                { title: t('fx.currency'), dataIndex: 'currency' },
                { title: t('fx.open'), dataIndex: 'openAmount', align: 'right', render: money },
                { title: t('fx.carrying'), dataIndex: 'carryingUsd', align: 'right', render: money },
                { title: t('fx.rate'), dataIndex: 'rate', align: 'right', render: rate },
                { title: t('fx.revalued'), dataIndex: 'revaluedUsd', align: 'right', render: money },
                { title: t('fx.difference'), dataIndex: 'difference', align: 'right', render: money },
                { title: t('fx.originalRate'), dataIndex: 'originalRate', align: 'right', render: rate },
                { title: t('fx.originalDifference'), dataIndex: 'originalDifference', align: 'right', render: money },
                { title: t('fx.change'), dataIndex: 'change', align: 'right', render: money },
              ]}
            />
          </>
        )}
      </Space>
    </Card>
  )
}
