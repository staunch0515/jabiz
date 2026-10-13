import {
  Badge,
  CopyButton,
  DataTable,
  FilterBar,
  filledFilters,
  notify,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  UI_NAMESPACE,
  type ColumnDef,
  type FilterField,
  type FilterRange,
  type FilterValues,
  type PaginationState,
} from '@jabiz/ui'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { AUDIT_FILTERS, type AuditFieldChange, type AuditRecord, type RevealRecord } from '../api/audit'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import UserName from '../components/UserName'
import AdminPage from '../layout/AdminPage'
import { formatDateTime } from '../meta/format'

const ACTION_VARIANTS: Record<string, 'success' | 'default' | 'destructive'> = {
  INSERT: 'success',
  UPDATE: 'default',
  DELETE: 'destructive',
}
const PAGE_SIZE = 50

type Query = Record<string, string | number | undefined>

function show(value: unknown): string {
  if (value === null || value === undefined) return ''
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

/** The text filters as query parameters, and a time range as `from` / `to`. */
function toQuery(filters: FilterValues, names: readonly string[], range: string): Query {
  const query: Query = {}
  for (const name of names) {
    const value = filters[name]
    if (typeof value === 'string' && value.trim()) query[name] = value.trim()
  }
  const time = filters[range] as FilterRange | undefined
  if (time?.from) query.from = time.from
  if (time?.to) query.to = time.to
  return query
}

/**
 * A page of a list the server pages (offset and limit, with a total): the previous page stays, unusable, while the
 * next loads; a new search starts at the first page.
 */
function usePagedList<Row>(key: string, filters: FilterValues, fetch: (query: Query) => Promise<{ items?: Row[]; total?: number }>) {
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const offset = pagination.pageIndex * pagination.pageSize
  const list = useQuery({
    queryKey: ['audit', key, filters, offset, pagination.pageSize],
    placeholderData: keepPreviousData,
    queryFn: () => fetch({ offset, limit: pagination.pageSize }),
  })
  useEffect(() => {
    if (list.error) notify.error(list.error instanceof ApiError ? list.error.display : String(list.error))
  }, [list.error])
  return {
    list,
    table: {
      data: list.data?.items ?? [],
      loading: list.isFetching,
      stale: list.isPlaceholderData,
      pagination,
      onPaginationChange: setPagination,
      rowCount: list.data?.total ?? 0,
      pageSizeOptions: [20, 50, 100],
    },
    firstPage: () => setPagination((current) => ({ ...current, pageIndex: 0 })),
  }
}

/** Each changed field of one record, before and after. Secrets arrive as *** from the server. */
export function ChangeTable({ record }: { record: AuditRecord }) {
  const { t } = useTranslation()
  const rows = Object.entries(record.changes ?? {}).map(([field, change]) => ({ field, ...(change as AuditFieldChange) }))
  return (
    <Table data-testid={`audit-changes-${record.recordNo}`} aria-label={t('audit.changed')}>
      <TableHeader>
        <TableRow>
          <TableHead className="w-48">{t('audit.field')}</TableHead>
          <TableHead>{t('audit.before')}</TableHead>
          <TableHead>{t('audit.after')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={row.field}>
            <TableCell className="font-mono text-xs">{row.field}</TableCell>
            <TableCell className="whitespace-normal break-all">
              {record.action !== 'INSERT' ? <del>{show(row.before)}</del> : show(row.before)}
            </TableCell>
            <TableCell className="whitespace-normal break-all">{show(row.after)}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

/** An entry's id, with a button copying it. */
function EntryId({ id, label }: { id?: string; label: string }) {
  const { t } = useTranslation()
  if (!id) return null
  return (
    <span className="inline-flex items-center gap-1">
      <span className="break-all">{id}</span>
      <CopyButton value={id} label={t('copy.copyValue', { ns: UI_NAMESPACE, value: label })} />
    </span>
  )
}

/**
 * The audit trail (docs/design/21-audit-retention.md section 1): who changed which entry, when, why, and each field's
 * value before and after, newest first. Filters start from the URL (`?entityType=&entityId=`, as the history page
 * links here); the server requires audit.read.
 */
export default function AuditPage() {
  const { t } = useTranslation()
  // A tab is read when first shown, then kept (hidden) with its filters and page while the other is shown.
  const [shown, setShown] = useState<ReadonlySet<string>>(() => new Set(['records']))
  const kept = (tab: string) => (shown.has(tab) ? true : undefined)
  return (
    <AdminPage title={t('audit.title')}>
      <Tabs
        defaultValue="records"
        className="gap-4"
        onValueChange={(tab) => setShown((current) => (current.has(tab) ? current : new Set([...current, tab])))}
      >
        <TabsList>
          <TabsTrigger value="records">{t('audit.records')}</TabsTrigger>
          <TabsTrigger value="reveals">{t('audit.reveals')}</TabsTrigger>
        </TabsList>
        <TabsContent value="records" forceMount={kept('records')} className="data-[state=inactive]:hidden">
          <AuditRecords />
        </TabsContent>
        <TabsContent value="reveals" forceMount={kept('reveals')} className="data-[state=inactive]:hidden">
          <RevealRecords />
        </TabsContent>
      </Tabs>
    </AdminPage>
  )
}

function AuditRecords() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const [filters, setFilters] = useState<FilterValues>(() =>
    filledFilters(Object.fromEntries(AUDIT_FILTERS.map((name) => [name, searchParams.get(name) ?? undefined]))),
  )
  const { table, firstPage } = usePagedList<AuditRecord>('records', filters, async (paging) => {
    const query: Query = { ...paging, ...toQuery(filters, AUDIT_FILTERS, 'recordedTime') }
    // The approvals of an entry belong to its trail: who approved, what and why.
    if (query.entityType && query.entityId) query.withApprovals = 'true'
    return unwrap(api.GET('/api/audit/records', { params: { query } }))
  })

  const fields = useMemo<FilterField[]>(
    () => [
      { kind: 'datetime-range', name: 'recordedTime', label: t('audit.recorded') },
      { kind: 'text', name: 'actorId', label: t('audit.actor') },
      { kind: 'text', name: 'entityType', label: t('audit.entityType') },
      { kind: 'text', name: 'entityId', label: t('audit.entityId') },
      { kind: 'text', name: 'processName', label: t('audit.process') },
      { kind: 'text', name: 'field', label: t('audit.field') },
    ],
    [t],
  )
  const columns = useMemo<ColumnDef<AuditRecord, unknown>[]>(
    () => [
      {
        id: 'recorded',
        header: t('audit.recorded'),
        cell: ({ row: { original: record } }) => (
          <span className="flex flex-col">
            <span>{record.recordedTime ? formatDateTime(record.recordedTime) : ''}</span>
            <span className="text-muted-foreground text-xs">
              <UserName id={record.actorId} />
            </span>
          </span>
        ),
      },
      { id: 'entityType', header: t('audit.entityType'), cell: ({ row }) => row.original.entityType },
      {
        id: 'entityId',
        header: t('audit.entityId'),
        cell: ({ row }) => <EntryId id={row.original.entityId} label={t('audit.entityId')} />,
      },
      {
        id: 'action',
        header: t('audit.action'),
        cell: ({ row }) => (
          <Badge variant={ACTION_VARIANTS[row.original.action ?? ''] ?? 'secondary'}>{row.original.action}</Badge>
        ),
      },
      {
        id: 'version',
        header: t('audit.version'),
        meta: { className: 'text-right tabular-nums' },
        cell: ({ row }) => row.original.versionNo,
      },
      { id: 'process', header: t('audit.process'), cell: ({ row }) => row.original.processName ?? '' },
      {
        id: 'changed',
        header: t('audit.changed'),
        cell: ({ row }) => Object.keys(row.original.changes ?? {}).join(', '),
      },
      {
        id: 'reason',
        header: t('audit.reason'),
        meta: { className: 'max-w-xs' },
        cell: ({ row }) => (
          <span className="block truncate" title={row.original.reason ?? undefined}>
            {row.original.reason}
          </span>
        ),
      },
    ],
    [t],
  )

  return (
    <div className="flex flex-col gap-4">
      <FilterBar
        fields={fields}
        values={filters}
        onSubmit={(values) => {
          setFilters(values)
          firstPage()
        }}
      />
      <div data-testid="audit-records">
        <DataTable
          label={t('audit.records')}
          columns={columns}
          getRowId={(record) => String(record.recordNo)}
          renderExpanded={(record) => <ChangeTable record={record} />}
          {...table}
        />
      </div>
    </div>
  )
}

/**
 * Every display of masked values in plain text (docs/design/10-security.md section 13.1): one value on request, or
 * the plain columns of a template run or an export, with who and when.
 */
function RevealRecords() {
  const { t } = useTranslation()
  const [filters, setFilters] = useState<FilterValues>({})
  const { table, firstPage } = usePagedList<RevealRecord>('reveals', filters, (paging) =>
    unwrap(
      api.GET('/api/audit/reveals', {
        params: { query: { ...paging, ...toQuery(filters, ['actorId', 'entityType', 'entityId'], 'revealedAt') } },
      }),
    ),
  )
  const fields = useMemo<FilterField[]>(
    () => [
      { kind: 'datetime-range', name: 'revealedAt', label: t('audit.revealedAt') },
      { kind: 'text', name: 'actorId', label: t('audit.actor') },
      { kind: 'text', name: 'entityType', label: t('audit.entityType') },
      { kind: 'text', name: 'entityId', label: t('audit.entityId') },
    ],
    [t],
  )
  const columns = useMemo<ColumnDef<RevealRecord, unknown>[]>(
    () => [
      {
        id: 'revealedAt',
        header: t('audit.revealedAt'),
        cell: ({ row }) => (row.original.revealedAt ? formatDateTime(row.original.revealedAt) : ''),
      },
      { id: 'actor', header: t('audit.actor'), cell: ({ row }) => <UserName id={row.original.actorId} /> },
      {
        id: 'kind',
        header: t('audit.revealKind'),
        cell: ({ row }) => <Badge variant="secondary">{t(`audit.revealKinds.${row.original.kind}`)}</Badge>,
      },
      {
        id: 'resource',
        header: t('audit.revealResource'),
        meta: { className: 'max-w-xs' },
        cell: ({ row }) => (
          <span className="block truncate" title={row.original.resource ?? undefined}>
            {row.original.resource}
          </span>
        ),
      },
      { id: 'entityType', header: t('audit.entityType'), cell: ({ row }) => row.original.entity ?? '' },
      {
        id: 'entityId',
        header: t('audit.entityId'),
        cell: ({ row }) => <EntryId id={row.original.entityId} label={t('audit.entityId')} />,
      },
      { id: 'fields', header: t('audit.revealFields'), cell: ({ row }) => (row.original.fields ?? []).join(', ') },
      {
        id: 'rows',
        header: t('audit.revealRows'),
        meta: { className: 'text-right tabular-nums' },
        cell: ({ row }) => row.original.rowCount,
      },
    ],
    [t],
  )
  return (
    <div className="flex flex-col gap-4">
      <FilterBar
        fields={fields}
        values={filters}
        onSubmit={(values) => {
          setFilters(values)
          firstPage()
        }}
      />
      <div data-testid="audit-reveals">
        <DataTable label={t('audit.reveals')} columns={columns} getRowId={(r) => r.revealId ?? ''} {...table} />
      </div>
    </div>
  )
}
