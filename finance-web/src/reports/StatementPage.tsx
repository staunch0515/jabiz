import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, paths, useAuth } from '@jabiz/admin'
import { Alert, Button, Card, Input, Select, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useSearchParams } from 'react-router'
import { accountsOf, exportStatement, issue, layoutAccounts, LIMIT, PERMISSIONS, runStatement, STATEMENTS,
  type ExportFormat, type IssueOutput, type Statement, type StatementKind, type StatementRow } from './api'
import { figure } from './format'
import { lineDetailPath } from './paths'

const message = (e: unknown) => (e instanceof ApiError ? e.display : String(e))
const DAY = /^\d{4}-\d{2}-\d{2}$/

/**
 * A financial statement (FIN-RP-002 to 005; ROADMAP F9 decision D12): its parameters, its lines with the comparative
 * columns as the server computes them (shown exactly, never added up here, so a total is the stored one), each figure
 * of a drillable column opening the entries behind it with the same day and knownAt (FIN-RP-006); exported and
 * issued through the platform, the issue checked by the finance process (unmapped or unexplained accounts refused).
 */
export default function StatementPage() {
  const { kind } = useParams<{ kind: StatementKind }>()
  const statement = kind ? STATEMENTS[kind] : undefined
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  if (!statement) return <Alert type="error" showIcon message={t('reports.unknown')} />
  return <StatementView key={statement.kind} statement={statement} />
}

function StatementView({ statement }: { statement: Statement }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const [search, setSearch] = useSearchParams()
  const asked = Object.fromEntries(search.entries())
  const [draft, setDraft] = useState<Record<string, string>>(asked)
  const ready = DAY.test(asked[statement.day] ?? '')

  const rows = useQuery({
    queryKey: ['fin', 'statement', statement.kind, asked],
    queryFn: () => runStatement(statement, asked),
    enabled: ready,
  })
  const layout = useQuery({
    queryKey: ['fin', 'layout', asked.layout ?? statement.layout, asked.layoutVersion],
    queryFn: () => layoutAccounts(asked.layout ?? statement.layout!, asked.layoutVersion),
    enabled: ready && statement.layout !== undefined,
  })

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [issued, setIssued] = useState<IssueOutput | null>(null)
  const act = async (work: () => Promise<void>) => {
    setBusy(true)
    setError(null)
    try {
      await work()
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  const drill = (row: StatementRow, column: { key: string; span?: string }) => {
    const accounts = column.span && layout.data ? accountsOf(row, layout.data) : undefined
    if (!accounts || row[column.key] === null || row[column.key] === undefined) return figure(row[column.key])
    return (
      <Link data-testid={`drill-${row.lineCode}-${column.key}`} to={lineDetailPath({
        accounts, through: asked[statement.day], span: column.span, from: statement.kind === 'equity'
          ? asked.from : undefined, knownAt: asked.knownAt, adjustments: asked.adjustments,
        department: asked.department, location: asked.location, label: row.label,
      })}>{figure(row[column.key])}</Link>
    )
  }

  const items = rows.data?.items ?? []
  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">
        {t(`reports.${statement.kind}`)}
      </Typography.Title>
      <Card size="small">
        <Space wrap>
          {[statement.day, ...statement.params].map((name) => name === 'adjustments' ? (
            <Select key={name} aria-label={t(`reports.param.${name}`)} style={{ width: 200 }} allowClear
              placeholder={t(`reports.param.${name}`)} value={draft[name] || undefined}
              onChange={(value?: string) => setDraft({ ...draft, [name]: value ?? '' })} data-testid={`param-${name}`}
              options={[{ value: 'true', label: t('reports.withAdjustments') },
                { value: 'false', label: t('reports.withoutAdjustments') }]} />
          ) : (
            <Input key={name} aria-label={t(`reports.param.${name}`)} placeholder={t(`reports.param.${name}`)}
              style={{ width: name === 'knownAt' ? 240 : 150 }} value={draft[name] ?? ''}
              onChange={(e) => setDraft({ ...draft, [name]: e.target.value.trim() })} data-testid={`param-${name}`} />
          ))}
          <Button type="primary" disabled={!DAY.test(draft[statement.day] ?? '')} data-testid="run"
            onClick={() => {
              setIssued(null)
              setSearch(Object.fromEntries(Object.entries(draft).filter(([, v]) => v)))
            }}>{t('reports.run')}</Button>
        </Space>
      </Card>
      {error && <Alert type="error" showIcon closable message={error} data-testid="statement-error"
        onClose={() => setError(null)} />}
      {issued && (
        <Alert type="success" showIcon closable data-testid="issued" onClose={() => setIssued(null)}
          message={<span>{t('reports.issued', { runId: issued.runId })}{' '}
            <Link to={paths.reportArchive(statement.queryId)}>{t('reports.archive')}</Link></span>} />
      )}
      {ready && (
        <Space wrap>
          {(['pdf', 'xlsx', 'csv'] as ExportFormat[]).map((format) => (
            <Button key={format} disabled={busy} data-testid={`export-${format}`}
              onClick={() => void act(() => exportStatement(statement.queryId, format, asked))}>
              {t(`reports.export.${format}`)}
            </Button>
          ))}
          {can(PERMISSIONS.issue) && (
            <Button disabled={busy} loading={busy} data-testid="issue"
              onClick={() => void act(async () => setIssued(await issue(statement, asked, crypto.randomUUID())))}>
              {t('reports.issue')}
            </Button>
          )}
        </Space>
      )}
      {items.length >= LIMIT && <Alert type="warning" showIcon message={t('reports.cut', { limit: LIMIT })} />}
      {ready && (
        <Table<StatementRow>
          data-testid="statement"
          size="small"
          rowKey="lineCode"
          loading={rows.isLoading}
          dataSource={items}
          pagination={false}
          locale={{ emptyText: rows.error ? message(rows.error) : t('reports.empty') }}
          rowClassName={(row) => `statement-${row.kind.toLowerCase()}`}
          columns={[
            {
              title: t('reports.line'), dataIndex: 'label',
              render: (label: string, row) => {
                const weight = row.kind === 'TOTAL' || row.kind === 'HEADING' ? 600 : undefined
                const indent = row.lineCode.includes('.') && !row.lineCode.startsWith('UNMAPPED.') ? 24 : 0
                return (
                  <span style={{ fontWeight: weight, paddingLeft: indent }} data-testid={`line-${row.lineCode}`}>
                    {label}{' '}
                    {(row.kind === 'UNMAPPED' || row.kind === 'UNCLASSIFIED') && <Tag color="warning">
                      {t(`reports.kind.${row.kind}`)}</Tag>}
                  </span>
                )
              },
            },
            ...statement.columns.map((column) => ({
              title: t(`reports.column.${column.key}`),
              key: column.key,
              align: 'right' as const,
              render: (_: unknown, row: StatementRow) => (
                <span style={{ fontWeight: row.kind === 'TOTAL' ? 600 : undefined }}
                  data-testid={`cell-${row.lineCode}-${column.key}`}>{drill(row, column)}</span>
              ),
            })),
          ]}
        />
      )}
    </Space>
  )
}
