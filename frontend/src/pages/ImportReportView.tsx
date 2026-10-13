import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  DataTable,
  DescriptionList,
  SegmentedControl,
  type ColumnDef,
} from '@jabiz/ui'
import { CircleAlert, CircleCheck } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ImportEntry, ImportIssue, ImportReport } from '../api/imports'
import { useLocalPage } from '../components/useLocalPage'
import { fieldLabel } from '../meta/imports'

type Result = NonNullable<ImportReport['results']>[number]

const STATUS_VARIANTS = { ok: 'success', duplicate: 'secondary', error: 'destructive' } as const

/** Problems of an import, a run or an inspection: where, which field, what; 20 to a page. */
export function IssueTable({ entry, issues }: { entry?: ImportEntry; issues: ImportIssue[] }) {
  const { t } = useTranslation()
  const page = useLocalPage(issues, 20)
  const columns = useMemo<ColumnDef<ImportIssue, unknown>[]>(
    () => [
      {
        id: 'row',
        header: t('imports.row'),
        meta: { className: 'w-16' },
        cell: ({ row }) => (row.original.row ? row.original.row : t('imports.file')),
      },
      { id: 'location', header: t('imports.location'), cell: ({ row }) => row.original.location },
      { id: 'field', header: t('imports.field'), cell: ({ row }) => fieldLabel(entry, row.original.field) },
      { id: 'message', header: t('imports.problem'), cell: ({ row }) => row.original.message },
    ],
    [t, entry],
  )
  return (
    <div data-testid="import-issues">
      <DataTable
        label={t('imports.issuesTable')}
        columns={columns}
        getRowId={(issue, index) => `${index}-${issue.row}-${issue.field}-${issue.code}`}
        {...page.paging}
        data={page.data}
      />
    </div>
  )
}

/**
 * What a preview or a commit found (docs/design/20-imports.md section 5): the figures, the control totals, every
 * problem, and each record with its outcome.
 */
export default function ImportReportView({ entry, report }: { entry?: ImportEntry; report: ImportReport }) {
  const { t } = useTranslation()
  const [show, setShow] = useState<'all' | 'error' | 'duplicate'>('all')
  const results = useMemo(
    () => (report.results ?? []).filter((r) => show === 'all' || r.status === show),
    [report.results, show],
  )
  const page = useLocalPage(results, 50)
  const issues = report.issues ?? []
  const columns = useMemo<ColumnDef<Result, unknown>[]>(
    () => [
      { id: 'number', header: t('imports.row'), meta: { className: 'w-16' }, cell: ({ row }) => row.original.number },
      {
        id: 'status',
        header: t('imports.status'),
        cell: ({ row }) => {
          const status = row.original.status ?? ''
          return (
            <Badge variant={STATUS_VARIANTS[status as keyof typeof STATUS_VARIANTS] ?? 'outline'}>
              {t(`imports.statuses.${status}`)}
            </Badge>
          )
        },
      },
      ...(entry?.fields ?? []).map(
        (field): ColumnDef<Result, unknown> => ({
          id: field.name ?? '',
          header: field.label,
          cell: ({ row }) => {
            const value = (row.original.values as Record<string, unknown> | undefined)?.[field.name ?? '']
            return value === null || value === undefined ? '' : String(value)
          },
        }),
      ),
    ],
    [t, entry],
  )

  return (
    <div className="flex flex-col gap-4" data-testid="import-report">
      {report.accepted ? (
        <Alert variant="success">
          <CircleCheck aria-hidden />
          <AlertTitle>{t(report.committed ? 'imports.committed' : 'imports.accepted')}</AlertTitle>
        </Alert>
      ) : (
        <Alert variant="destructive">
          <CircleAlert aria-hidden />
          <AlertTitle>{t('imports.problems', { count: issues.length })}</AlertTitle>
          {report.runId && !report.committed && <AlertDescription>{t('imports.rejected')}</AlertDescription>}
        </Alert>
      )}
      <DescriptionList
        columns={4}
        items={[
          { key: 'records', label: t('imports.records'), value: report.records },
          { key: 'rows', label: t('imports.rows'), value: report.rows },
          { key: 'duplicates', label: t('imports.duplicates'), value: report.duplicates },
          { key: 'processed', label: t('imports.processed'), value: `${report.processed} / ${report.units}` },
          ...Object.entries(report.totals ?? {}).map(([field, total]) => ({
            key: `total-${field}`,
            label: `${t('imports.total')} ${fieldLabel(entry, field)}`,
            value: <span data-testid={`import-total-${field}`}>{String(total)}</span>,
          })),
        ]}
      />
      {issues.length > 0 && <IssueTable entry={entry} issues={issues} />}
      <SegmentedControl
        aria-label={t('imports.show.label')}
        value={show}
        onChange={setShow}
        options={[
          { value: 'all', label: t('imports.show.all') },
          { value: 'error', label: t('imports.show.error') },
          { value: 'duplicate', label: t('imports.show.duplicate') },
        ]}
      />
      <div data-testid="import-rows">
        <DataTable
          label={t('imports.rowsTable')}
          columns={columns}
          getRowId={(result) => String(result.number)}
          {...page.paging}
          data={page.data}
        />
      </div>
    </div>
  )
}
