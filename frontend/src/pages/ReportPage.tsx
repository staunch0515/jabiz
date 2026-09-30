import { PageContainer, ProForm, ProFormDateTimePicker, ProTable, type ProColumns } from '@ant-design/pro-components'
import { DownloadOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Dropdown, Result, Spin, Typography } from 'antd'
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { exportQuery, type ExportFormat, type ExportRequest } from '../api/reports'
import { renderNode } from '../components/SchemaInputs'
import type { Row } from '../meta/columns'
import { buildColumns } from '../meta/columns'
import { formatAmount } from '../meta/format'
import { useQueryCatalog } from '../meta/hooks'
import { scaleOf } from '../meta/kinds'
import { buildFilters, buildSorts, listViewOf } from '../meta/listQuery'
import { InvalidJson, inputNodes, toProcessInput, type JsonSchema } from '../meta/processForm'
import { pointInTimeOf, reportEntity } from '../meta/reports'
import type { Violation } from '../meta/types'

const EXPORT_FORMATS: ExportFormat[] = ['xlsx', 'pdf', 'csv']

/** What the table runs: the parameters and point in time of the last submitted form. */
interface Run {
  params: Record<string, unknown>
  asOf?: string
  knownAt?: string
  /** Changes with every submission, so that the table asks again even for the same values. */
  serial: number
}

function instant(value: unknown): string | undefined {
  if (value === undefined || value === null || value === '') return undefined
  const text = typeof value === 'object' && 'toISOString' in (value as object)
    ? (value as { toISOString(): string }).toISOString()
    : String(value)
  return text
}

/**
 * Runs one report (docs/design/19-reports.md section 3.3): a form generated from the template's parameters, the
 * point in time when the template accepts one, and the result as a table paged, filtered and sorted by the server
 * within the template's whitelist. Amounts show negatives in parentheses, as financial reports do. The rows on screen
 * - all of them - can be exported as Excel, PDF or CSV (section 4).
 */
export default function ReportPage() {
  const { t, i18n } = useTranslation()
  const { message } = App.useApp()
  const [searchParams] = useSearchParams()
  const id = searchParams.get('id') ?? ''
  const catalog = useQueryCatalog()
  const entry = catalog.data?.find((q) => q.id === id)
  const nodes = useMemo(() => (entry ? inputNodes(entry.params as JsonSchema) : []), [entry])
  const entity = useMemo(() => (entry ? reportEntity(entry) : undefined), [entry])
  const view = entity ? listViewOf(entity, 'default') : undefined
  const needsInput = nodes.some((n) => n.required)
  const [run, setRun] = useState<Run | null>(null)
  const [violations, setViolations] = useState<Violation[]>([])
  const effective = run ?? (entry && !needsInput ? { params: {}, serial: 0 } : null)
  // The filters and sort of the rows on screen: an export gives the same rows, all of them.
  const shown = useRef<Pick<ExportRequest, 'filters' | 'sorts'>>({})
  const [exporting, setExporting] = useState(false)

  const download = async (format: ExportFormat) => {
    if (!effective) return
    setExporting(true)
    try {
      await exportQuery(id, format, {
        params: effective.params,
        asOf: effective.asOf,
        knownAt: effective.knownAt,
        ...shown.current,
      })
    } catch (e) {
      message.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setExporting(false)
    }
  }

  const columns = useMemo<ProColumns<Row>[]>(() => {
    if (!entity) return []
    return buildColumns(entity, view, {}, t, i18n.language).map((column) => {
      const field = entity.fields.find((f) => f.name === column.dataIndex)
      if (!field || (field.type !== 'monetary' && field.type !== 'numeric')) return column
      const scale = scaleOf(field) ?? 0
      return {
        ...column,
        align: 'right',
        render: (_, row) => formatAmount(row[field.name], { scale, negative: 'parentheses' }),
      }
    })
  }, [entity, view, t, i18n.language])

  if (catalog.isLoading) return <Spin style={{ margin: 48 }} />
  if (!entry || !entry.report || !entity) return <Result status="404" title={id} />
  const pointInTime = pointInTimeOf(entry)

  return (
    <PageContainer
      title={<span data-testid="page-title">{entry.title}</span>}
      subTitle={<Typography.Text type="secondary">{t('reports.version', { version: entry.version?.slice(0, 12) })}</Typography.Text>}
      content={entry.description}
    >
      {violations.length > 0 && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          data-testid="report-error"
          message={violations.map((v) => `${v.field ? `${v.field}: ` : ''}${v.message}`).join(' ')}
        />
      )}
      {(nodes.length > 0 || pointInTime === 'request') && (
        <Card style={{ marginBottom: 16 }}>
          <ProForm
            name="report"
            layout="inline"
            dateFormatter={false}
            submitter={{ searchConfig: { submitText: t('reports.run') }, resetButtonProps: false }}
            onFinish={async (values: Record<string, unknown>) => {
              setViolations([])
              const { __asOf, __knownAt, ...params } = values
              try {
                setRun({
                  params: toProcessInput(nodes, params),
                  asOf: instant(__asOf),
                  knownAt: instant(__knownAt),
                  serial: (run?.serial ?? 0) + 1,
                })
              } catch (e) {
                if (e instanceof InvalidJson) {
                  setViolations([{ field: e.path, ruleCode: 'INVALID_VALUE', message: t('process.invalidJson') }])
                  return false
                }
                throw e
              }
              return true
            }}
          >
            {nodes.map((node) => renderNode(node, t))}
            {pointInTime === 'request' && (
              <>
                <ProFormDateTimePicker name="__asOf" label={t('reports.asOf')} fieldProps={{ 'data-testid': 'report-as-of' } as object} />
                <ProFormDateTimePicker name="__knownAt" label={t('reports.knownAt')} />
              </>
            )}
          </ProForm>
        </Card>
      )}
      {effective ? (
        <ProTable<Row>
          // A new run (or report) starts a new table, so its search values hold nothing but filters.
          key={`${id}#${effective.serial}`}
          toolBarRender={() => [
            <Dropdown
              key="export"
              menu={{
                items: EXPORT_FORMATS.map((format) => ({ key: format, label: t(`reports.formats.${format}`) })),
                onClick: ({ key }) => void download(key as ExportFormat),
              }}
            >
              <Button icon={<DownloadOutlined />} loading={exporting} data-testid="report-export">
                {t('reports.export')}
              </Button>
            </Dropdown>,
          ]}
          rowKey="__key"
          columns={columns}
          dateFormatter={(value) => value.toISOString()}
          scroll={{ x: 'max-content' }}
          pagination={{ defaultPageSize: 50, showSizeChanger: true }}
          search={view?.filters?.length ? { labelWidth: 'auto', defaultCollapsed: true } : false}
          data-testid="report-table"
          request={async (params, sort) => {
            const { current = 1, pageSize = 50, ...values } = params
            setViolations([])
            shown.current = {
              filters: buildFilters(entity, view, values),
              sorts: buildSorts(view, sort as Record<string, string>),
            }
            try {
              const result = await unwrap(
                api.POST('/api/queries/{queryId}', {
                  params: { path: { queryId: id } },
                  body: {
                    params: effective.params,
                    asOf: effective.asOf,
                    knownAt: effective.knownAt,
                    ...shown.current,
                    offset: (current - 1) * pageSize,
                    limit: pageSize,
                  },
                }),
              )
              const data = (result.items ?? []).map((item, i) => ({
                ...(item as Record<string, unknown>),
                __key: String((current - 1) * pageSize + i),
              })) as unknown as Row[]
              return { data, total: result.total ?? 0, success: true }
            } catch (e) {
              if (e instanceof ApiError && e.violations.length > 0) setViolations(e.violations)
              else message.error(e instanceof ApiError ? e.display : String(e))
              return { data: [], total: 0, success: false }
            }
          }}
        />
      ) : (
        <Card>
          <Typography.Text type="secondary" data-testid="report-not-run">{t('reports.notRun')}</Typography.Text>
        </Card>
      )}
    </PageContainer>
  )
}
