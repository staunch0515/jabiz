import {
  Alert,
  AlertDescription,
  Button,
  ConfirmDialog,
  DataTable,
  DateTimePicker,
  FilterBar,
  Label,
  notify,
  PageState,
  type ColumnDef,
  type FilterValues,
  type PaginationState,
  type SortingState,
} from '@jabiz/ui'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { History, Info, Plus, ScrollText } from 'lucide-react'
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
import EntityFormDrawer from '../components/EntityFormDrawer'
import RowActions from '../components/RowActions'
import AdminPage from '../layout/AdminPage'
import { actionsFor } from '../meta/actions'
import { toRow, type Row } from '../meta/cells'
import { dataColumns, defaultSorting, filterFields } from '../meta/dataColumns'
import { formatDateTime } from '../meta/format'
import { useDataset, useDatasets, useDictionaries, useEntityMeta, useProcesses } from '../meta/hooks'
import { buildFilters, buildSorts, listViewOf } from '../meta/listQuery'
import { useEntityMetas, useReferenceLabels } from '../meta/references'
import type { DatasetEntry, DictItem, EntityInstance, EntityMeta } from '../meta/types'
import { paths } from './paths'

const LINK = 'h-auto px-1'
const DEFAULT_PAGE_SIZE = 20

/**
 * The generated list page of any dataset (docs/design/12-frontend.md section 5): columns, filters and sorting from
 * the list view, create / edit / delete through the dataset API when the user may write, the processes acting on the
 * entity as row actions, references shown by their labels, and — for temporal entities — the data at another point
 * in time (in the address, read-only) and the history of each entry.
 */
export default function DatasetListPage() {
  const { t } = useTranslation()
  const { datasetId = '' } = useParams()
  const dataset = useDataset(datasetId)
  const entity = useEntityMeta(dataset.data?.entity)
  const dictionaries = useDictionaries(entity.data)

  if (dataset.isLoading || (dataset.data && entity.isLoading)) return <PageState kind="loading" />
  if (!dataset.data) return <PageState kind="notFound" titleAs="h1" title={datasetId} />
  if (entity.error || !entity.data) {
    return (
      <PageState kind="error" titleAs="h1" title={entity.error instanceof ApiError ? entity.error.display : t('app.error')} />
    )
  }
  // Another dataset starts afresh: its own page, sorting and filters.
  return <DatasetList key={datasetId} dataset={dataset.data} entity={entity.data} dictionaries={dictionaries} />
}

function DatasetList({
  dataset,
  entity,
  dictionaries,
}: {
  dataset: DatasetEntry
  entity: EntityMeta
  dictionaries: Record<string, DictItem[]>
}) {
  const { t, i18n } = useTranslation()
  const { can } = useAuth()
  const datasetId = dataset.id
  const [searchParams, setSearchParams] = useSearchParams()
  // The entry in the form sheet; the sheet stays mounted when closed, so it slides out and focus returns to the row.
  const [editing, setEditing] = useState<{ instance?: EntityInstance; open: boolean } | undefined>(undefined)
  const closeForm = () => setEditing((current) => current && { ...current, open: false })
  const datasets = useDatasets()
  const processes = useProcesses()
  const asOfId = useId()
  const knownAtId = useId()

  const asOf = searchParams.get('asOf')
  const knownAt = searchParams.get('knownAt')
  const timeTravel = !!(asOf || knownAt)

  const view = listViewOf(entity, dataset.listView)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: DEFAULT_PAGE_SIZE })
  const [sorting, setSorting] = useState<SortingState>(() => defaultSorting(entity, view, can))
  const [filters, setFilters] = useState<FilterValues>({})
  // Another point in time starts at the first page again.
  const [shownTime, setShownTime] = useState(`${asOf}|${knownAt}`)
  if (shownTime !== `${asOf}|${knownAt}`) {
    setShownTime(`${asOf}|${knownAt}`)
    setPagination({ ...pagination, pageIndex: 0 })
  }

  const body = {
    filters: buildFilters(entity, view, filters),
    sorts: buildSorts(view, sorting),
    offset: pagination.pageIndex * pagination.pageSize,
    limit: pagination.pageSize,
    asOf: asOf ?? undefined,
    knownAt: knownAt ?? undefined,
  }
  const query = useQuery({
    queryKey: ['dataset-query', datasetId, body],
    // The previous page stays while the next loads, as the table keeps its height and focus.
    placeholderData: keepPreviousData,
    queryFn: async () => {
      const result = await unwrap(
        api.POST('/api/datasets/{resourceId}/query', { params: { path: { resourceId: datasetId } }, body }),
      )
      return { rows: (result.items ?? []).map(toRow), total: result.total ?? 0 }
    },
  })
  const { refetch } = query
  useEffect(() => {
    if (query.error) notify.error(query.error instanceof ApiError ? query.error.display : String(query.error))
  }, [query.error])
  // A page beyond the last (its rows deleted, from here or elsewhere) shows the last page there is.
  const total = query.data?.total
  if (
    total !== undefined &&
    !query.isPlaceholderData &&
    pagination.pageIndex > 0 &&
    pagination.pageIndex * pagination.pageSize >= total
  ) {
    setPagination({ ...pagination, pageIndex: Math.max(0, Math.ceil(total / pagination.pageSize) - 1) })
  }
  // Where focus goes when what had it went with its row (a deleted entry's buttons).
  const tableRef = useRef<HTMLTableElement>(null)
  const focusTable = useCallback(() => tableRef.current, [])

  const rows = query.data?.rows
  const targets = useEntityMetas(entity.fields.flatMap((f) => (f.type === 'reference' ? [f.targetEntity] : [])))
  const labels = useReferenceLabels(entity, rows ?? [], datasets.data ?? [], targets)
  const actions = useMemo(() => actionsFor(entity.entity, processes.data ?? []), [entity.entity, processes.data])

  const canWrite = !!dataset.canWrite && !timeTravel
  // A write-once entity is only inserted (docs/design/04-temporal-append-only.md section 5.4).
  const canChange = canWrite && !entity.writeOnce
  const showHistory = !!dataset.temporal && !!dataset.allowTimeTravel
  // Every entity, plain or temporal, has an audit trail (docs/design/21-audit-retention.md section 1.4).
  const showAudit = can('audit.read')

  // Read again before the confirmation closes, so focus moves on from a row that is gone.
  const remove = useCallback(
    async (row: Row) => {
      try {
        await unwrap(
          api.POST('/api/datasets/{resourceId}/commit', {
            params: { path: { resourceId: datasetId } },
            body: { changes: [{ action: 'DELETE', id: row.__instance.id, version: row.__instance.version }] },
          }),
        )
        notify.success(t('list.deleted'))
        await refetch()
      } catch (e) {
        notify.error(e instanceof ApiError ? e.display : String(e))
      }
    },
    [datasetId, t, refetch],
  )

  const columns = useMemo<ColumnDef<Row, unknown>[]>(
    () => [
      ...dataColumns(entity, view, dictionaries, t, i18n.language, {
        labels,
        defaultLocale: entity.defaultLocale,
        can,
        // At another point in time there is no current value to show in plain.
        datasetId: timeTravel ? undefined : datasetId,
      }),
      {
        id: '__actions',
        header: t('list.actions'),
        meta: { pinned: 'right' },
        cell: ({ row: { original: row } }) => (
          <span className="flex flex-wrap items-center gap-1">
            <Button
              variant="link"
              size="sm"
              className={LINK}
              onClick={() => setEditing({ instance: row.__instance, open: true })}
              data-testid={canChange ? 'row-edit' : 'row-view'}
            >
              {t(canChange ? 'list.edit' : 'list.view')}
            </Button>
            {!timeTravel && (
              <RowActions
                actions={actions}
                id={row.__instance.id}
                attributes={row}
                onDone={() => refetch()}
                returnFocus={focusTable}
              />
            )}
            {canChange && (
              <ConfirmDialog
                title={t('list.deleteConfirm')}
                destructive
                onConfirm={() => remove(row)}
                returnFocus={focusTable}
                trigger={
                  <Button variant="link" size="sm" className={`${LINK} text-destructive`} data-testid="row-delete">
                    {t('list.delete')}
                  </Button>
                }
              />
            )}
            {showHistory && (
              <Button variant="link" size="sm" className={LINK} asChild>
                <Link to={paths.history(datasetId, String(row.__instance.id))} data-testid="row-history">
                  <History aria-hidden />
                  {t('list.history')}
                </Link>
              </Button>
            )}
            {showAudit && (
              <Button variant="link" size="sm" className={LINK} asChild>
                <Link to={paths.audit(entity.entity, String(row.__instance.id))} data-testid="row-audit">
                  <ScrollText aria-hidden />
                  {t('audit.open')}
                </Link>
              </Button>
            )}
          </span>
        ),
      },
    ],
    [entity, view, dictionaries, t, i18n.language, labels, can, timeTravel, datasetId, canChange, actions, showHistory, showAudit, refetch, remove, focusTable],
  )
  const filterBarFields = useMemo(() => filterFields(entity, view, dictionaries, t, can), [entity, view, dictionaries, t, can])

  const setTime = (key: 'asOf' | 'knownAt', value: string | null) => {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(key, value)
    else next.delete(key)
    setSearchParams(next, { replace: true })
  }

  return (
    <AdminPage
      title={entity.label}
      description={<code className="font-mono text-xs">{dataset.id}</code>}
      breadcrumb={[{ label: t('nav.datasets'), to: '/data' }, { label: entity.label }]}
      actions={
        canWrite && (
          <Button onClick={() => setEditing({ open: true })} data-testid="create">
            <Plus aria-hidden />
            {t('list.create')}
          </Button>
        )
      }
    >
      {showHistory && (
        <div className="flex flex-wrap items-end gap-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={asOfId}>{t('list.asOf')}</Label>
            <DateTimePicker id={asOfId} value={asOf} onChange={(value) => setTime('asOf', value)} clearable data-testid="as-of" />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={knownAtId}>{t('list.knownAt')}</Label>
            <DateTimePicker id={knownAtId} value={knownAt} onChange={(value) => setTime('knownAt', value)} clearable />
          </div>
        </div>
      )}
      {timeTravel && (
        <Alert data-testid="time-travel">
          <Info aria-hidden />
          <AlertDescription>{t('list.timeTravelHint', { time: formatDateTime(asOf ?? knownAt) })}</AlertDescription>
        </Alert>
      )}
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
      <DataTable
        label={entity.label}
        columns={columns}
        data={rows ?? []}
        getRowId={(row) => row.__key}
        loading={query.isFetching}
        stale={query.isPlaceholderData}
        tableRef={tableRef}
        pagination={pagination}
        onPaginationChange={setPagination}
        rowCount={query.data?.total}
        sorting={sorting}
        onSortingChange={(next) => {
          setSorting(next)
          setPagination({ ...pagination, pageIndex: 0 })
        }}
      />
      {editing !== undefined && (
        <EntityFormDrawer
          dataset={dataset}
          entity={entity}
          dictionaries={dictionaries}
          instance={editing.instance}
          readOnly={editing.instance ? !canChange : !canWrite}
          historical={timeTravel}
          open={editing.open}
          onOpenChange={(open) => {
            if (!open) closeForm()
          }}
          onSaved={() => {
            closeForm()
            void refetch()
          }}
        />
      )}
    </AdminPage>
  )
}
