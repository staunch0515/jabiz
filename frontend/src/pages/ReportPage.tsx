import { api, unwrap, ApiError, useAuth, runProcess, formatAmount } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Button,
  Card,
  CardContent,
  ConfirmDialog,
  DataTable,
  DateTimePicker,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  FilterBar,
  Label,
  notify,
  PageState,
  Spinner,
  type ColumnDef,
  type FilterValues,
  type PaginationState,
  type SortingState,
} from '@jabiz/ui'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { CircleAlert, Download, ShieldCheck } from 'lucide-react'
import { useCallback, useEffect, useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate, useSearchParams } from 'react-router'
import { exportQuery, type ExportFormat } from '../api/reports'
import SchemaForm from '../components/SchemaForm'
import AdminPage from '../layout/AdminPage'
import type { Row } from '../meta/cells'
import { dataColumns, defaultSorting, filterFields } from '../meta/dataColumns'
import { useQueryCatalog } from '../meta/hooks'
import { scaleOf } from '../meta/kinds'
import { buildFilters, buildSorts, listViewOf } from '../meta/listQuery'
import { InvalidJson, inputNodes, toProcessInput, type JsonSchema } from '../meta/processForm'
import { pointInTimeOf, reportEntity, type QueryEntry } from '../meta/reports'
import type { EntityMeta, ListViewMeta, Violation } from '../meta/types'
import { paths } from './paths'

const EXPORT_FORMATS: ExportFormat[] = ['xlsx', 'pdf', 'csv']
const DEFAULT_PAGE_SIZE = 50

/** What the table runs: the parameters and point in time of the last submitted form. */
interface Run {
  params: Record<string, unknown>
  asOf?: string
  knownAt?: string
  /** Changes with every submission, so that the table asks again even for the same values. */
  serial: number
}

const NO_VIOLATIONS: Violation[] = []

/**
 * Runs one report (docs/design/19-reports.md section 3.3): a form generated from the template's parameters, the
 * point in time when the template accepts one, and the result as a table paged, filtered and sorted by the server
 * within the template's whitelist. Amounts show negatives in parentheses, as financial reports do. The rows on screen
 * - all of them - can be exported as Excel, PDF or CSV (section 4); with `report.issue` the run can be issued, its
 * contents archived to be shown again exactly (section 5).
 */
export default function ReportPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const id = searchParams.get('id') ?? ''
  const catalog = useQueryCatalog()
  const entry = catalog.data?.find((q) => q.id === id)
  const nodes = useMemo(() => (entry ? inputNodes(entry.params as JsonSchema) : []), [entry])
  const entity = useMemo(() => (entry ? reportEntity(entry) : undefined), [entry])
  const view = entity ? listViewOf(entity, 'default') : undefined
  const needsInput = nodes.some((n) => n.required)
  const [run, setRun] = useState<Run | null>(null)
  const [formViolations, setFormViolations] = useState<Violation[]>(NO_VIOLATIONS)
  const [queryViolations, setQueryViolations] = useState<Violation[]>(NO_VIOLATIONS)
  // The point in time of a template that takes one from the request; typed text that is no time keeps it from running.
  const [asOf, setAsOf] = useState<string | null>(null)
  const [knownAt, setKnownAt] = useState<string | null>(null)
  const [timeInvalid, setTimeInvalid] = useState<ReadonlySet<string>>(new Set())
  const asOfId = useId()
  const knownAtId = useId()
  const effective = run ?? (entry && !needsInput ? { params: {}, serial: 0 } : null)

  const markInvalid = (name: string) => (invalid: boolean) =>
    setTimeInvalid((current) => {
      const next = new Set(current)
      if (invalid) next.add(name)
      else next.delete(name)
      return next
    })

  if (catalog.isLoading) return <PageState kind="loading" />
  if (!entry || !entry.report || !entity) return <PageState kind="notFound" titleAs="h1" title={id} />
  const pointInTime = pointInTimeOf(entry)
  const violations = [...formViolations, ...queryViolations]

  const submit = (values: Record<string, unknown>) => {
    setFormViolations(NO_VIOLATIONS)
    if (timeInvalid.size > 0) return
    try {
      setRun({
        params: toProcessInput(nodes, values),
        asOf: asOf ?? undefined,
        knownAt: knownAt ?? undefined,
        serial: (run?.serial ?? 0) + 1,
      })
    } catch (e) {
      if (e instanceof InvalidJson) {
        setFormViolations([{ field: e.path, ruleCode: 'INVALID_VALUE', message: t('process.invalidJson') }])
        return
      }
      throw e
    }
  }

  return (
    <AdminPage
      title={entry.title}
      breadcrumb={[{ label: t('nav.reports'), to: '/reports' }, { label: entry.title }]}
      description={
        <span className="flex flex-col gap-1">
          <span>{t('reports.version', { version: entry.version?.slice(0, 12) })}</span>
          {entry.description && <span>{entry.description}</span>}
        </span>
      }
    >
      {violations.length > 0 && (
        <Alert variant="destructive" data-testid="report-error">
          <CircleAlert aria-hidden />
          <AlertDescription>
            {violations.map((v) => `${v.field ? `${v.field}: ` : ''}${v.message}`).join(' ')}
          </AlertDescription>
        </Alert>
      )}
      {(nodes.length > 0 || pointInTime === 'request') && (
        <Card>
          <CardContent>
            <SchemaForm nodes={nodes} submitLabel={t('reports.run')} onSubmit={submit}>
              {pointInTime === 'request' && (
                <div className="flex flex-wrap items-start gap-4">
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor={asOfId}>{t('reports.asOf')}</Label>
                    <DateTimePicker id={asOfId} value={asOf} onChange={setAsOf} clearable data-testid="report-as-of"
                      onInvalidChange={markInvalid('asOf')} />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <Label htmlFor={knownAtId}>{t('reports.knownAt')}</Label>
                    <DateTimePicker id={knownAtId} value={knownAt} onChange={setKnownAt} clearable
                      onInvalidChange={markInvalid('knownAt')} />
                  </div>
                </div>
              )}
            </SchemaForm>
          </CardContent>
        </Card>
      )}
      {effective ? (
        // A new run (or report) starts a new table: the first page, the default order, no filters.
        <ReportResult
          key={`${id}#${effective.serial}`}
          id={id}
          entry={entry}
          entity={entity}
          view={view}
          run={effective}
          onViolations={setQueryViolations}
        />
      ) : (
        <Card>
          <CardContent>
            <p className="text-muted-foreground" data-testid="report-not-run">
              {t('reports.notRun')}
            </p>
          </CardContent>
        </Card>
      )}
    </AdminPage>
  )
}

/** The rows of one run: filtered, sorted and paged by the server, exported or issued as shown. */
function ReportResult({
  id,
  entry,
  entity,
  view,
  run,
  onViolations,
}: {
  id: string
  entry: QueryEntry
  entity: EntityMeta
  view: ListViewMeta | undefined
  run: Run
  onViolations: (violations: Violation[]) => void
}) {
  const { t, i18n } = useTranslation()
  const { can } = useAuth()
  const navigate = useNavigate()
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: DEFAULT_PAGE_SIZE })
  // The template's whitelist decides; a report's masked columns are compared by nobody here, as before.
  const [sorting, setSorting] = useState<SortingState>(() => defaultSorting(entity, view))
  const [filters, setFilters] = useState<FilterValues>({})
  const [exporting, setExporting] = useState(false)

  // The filters and sort of the rows on screen: an export gives the same rows, all of them.
  const shown = { filters: buildFilters(entity, view, filters), sorts: buildSorts(view, sorting) }
  const offset = pagination.pageIndex * pagination.pageSize
  const body = {
    params: run.params,
    asOf: run.asOf,
    knownAt: run.knownAt,
    ...shown,
    offset,
    limit: pagination.pageSize,
  }
  const query = useQuery({
    queryKey: ['report', id, run.serial, body],
    placeholderData: keepPreviousData,
    // A refusal (400) or a query stopped at its time limit (503) does not get better by asking again.
    retry: false,
    queryFn: async () => {
      const result = await unwrap(api.POST('/api/queries/{queryId}', { params: { path: { queryId: id } }, body }))
      const rows = (result.items ?? []).map((item, i) => ({
        ...(item as Record<string, unknown>),
        __key: String(offset + i),
      })) as unknown as Row[]
      return { rows, total: result.total ?? 0 }
    },
  })
  const { error } = query
  useEffect(() => {
    if (error instanceof ApiError && error.violations.length > 0) {
      onViolations(error.violations)
      return
    }
    onViolations(NO_VIOLATIONS)
    if (error) notify.error(error instanceof ApiError ? error.display : String(error))
  }, [error, onViolations])

  const download = async (format: ExportFormat) => {
    setExporting(true)
    try {
      await exportQuery(id, format, { params: run.params, asOf: run.asOf, knownAt: run.knownAt, ...shown })
    } catch (e) {
      notify.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setExporting(false)
    }
  }

  // Issues the report with the parameters and point in time of the run on screen (all rows, not only the filtered).
  const issue = useCallback(async () => {
    try {
      await runProcess('REPORT_ISSUE', { templateId: id, params: run.params, asOf: run.asOf, knownAt: run.knownAt })
      notify.success(t('reports.issued'))
      navigate(paths.reportArchive(id))
    } catch (e) {
      notify.error(e instanceof ApiError ? e.display : String(e))
    }
  }, [id, run, t, navigate])

  const columns = useMemo<ColumnDef<Row, unknown>[]>(
    () =>
      dataColumns(entity, view, {}, t, i18n.language).map((column) => {
        const field = entity.fields.find((f) => f.name === column.id)
        if (!field || (field.type !== 'monetary' && field.type !== 'numeric')) return column
        const scale = scaleOf(field) ?? 0
        return {
          ...column,
          meta: { className: 'text-right tabular-nums' },
          cell: ({ row }) => formatAmount(row.original[field.name], { scale, negative: 'parentheses' }),
        }
      }),
    [entity, view, t, i18n.language],
  )
  const filterBarFields = useMemo(() => filterFields(entity, view, {}, t), [entity, view, t])

  return (
    <div className="flex flex-col gap-4">
      {filterBarFields.length > 0 && (
        <FilterBar
          fields={filterBarFields}
          values={filters}
          onSubmit={(values) => {
            // A new search starts at the first page.
            setFilters(values)
            setPagination({ ...pagination, pageIndex: 0 })
          }}
        />
      )}
      <div className="flex flex-wrap justify-end gap-2">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" disabled={exporting} aria-busy={exporting || undefined} data-testid="report-export">
              {exporting ? <Spinner className="text-current" /> : <Download aria-hidden />}
              {t('reports.export')}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {EXPORT_FORMATS.map((format) => (
              <DropdownMenuItem key={format} onSelect={() => void download(format)}>
                {t(`reports.formats.${format}`)}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
        {can('report.issue') && (
          <ConfirmDialog
            title={t('reports.issueConfirm')}
            confirmLabel={t('reports.issue')}
            cancelLabel={t('reports.cancel')}
            onConfirm={issue}
            trigger={
              <Button data-testid="report-issue">
                <ShieldCheck aria-hidden />
                {t('reports.issue')}
              </Button>
            }
          />
        )}
      </div>
      <div data-testid="report-table">
        <DataTable
          label={entry.title ?? id}
          columns={columns}
          data={query.data?.rows ?? []}
          getRowId={(row) => row.__key}
          loading={query.isFetching}
          stale={query.isPlaceholderData}
          pagination={pagination}
          onPaginationChange={setPagination}
          rowCount={query.data?.total}
          sorting={sorting}
          onSortingChange={(next) => {
            setSorting(next)
            setPagination({ ...pagination, pageIndex: 0 })
          }}
        />
      </div>
    </div>
  )
}
