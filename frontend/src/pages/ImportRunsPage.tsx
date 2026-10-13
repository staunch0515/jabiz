import {
  Badge,
  Button,
  DataTable,
  DescriptionList,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  notify,
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  type ColumnDef,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { exportImportRun, type ExportFormat, type ImportRunDetail, type ImportRunSummary } from '../api/imports'
import { ApiError } from '../api/problem'
import { useLocalPage } from '../components/useLocalPage'
import AdminPage from '../layout/AdminPage'
import { formatDateTime } from '../meta/format'
import { useImportCatalog } from '../meta/hooks'
import { IssueTable } from './ImportReportView'

const FORMATS: ExportFormat[] = ['pdf', 'xlsx', 'csv']

const fail = (e: unknown) => notify.error(e instanceof ApiError ? e.display : String(e))

/**
 * Committed imports and rejected attempts (docs/design/20-imports.md section 5), newest first, optionally of one
 * import (`?import=`): figures, notes, problems, and the report as a file. The server lists only runs of imports the
 * user may run.
 */
export default function ImportRunsPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const importId = searchParams.get('import') ?? undefined
  const catalog = useImportCatalog()
  // The run in the details sheet; kept while the sheet slides out, so focus returns to its button.
  const [detail, setDetail] = useState<{ run: ImportRunDetail; open: boolean } | null>(null)
  // The import filter comes from the URL: a new one asks again.
  const runs = useQuery({
    queryKey: ['import-runs', importId],
    queryFn: () => unwrap(api.GET('/api/imports/runs', { params: { query: { import: importId, limit: 200 } } })),
  })
  const page = useLocalPage(runs.data, 50)

  useEffect(() => {
    if (runs.error) fail(runs.error)
  }, [runs.error])

  const columns = useMemo<ColumnDef<ImportRunSummary, unknown>[]>(() => {
    const open = async (runId: string) => {
      try {
        setDetail({ run: await unwrap(api.GET('/api/imports/runs/{runId}', { params: { path: { runId } } })), open: true })
      } catch (e) {
        fail(e)
      }
    }
    const save = async (runId: string, format: ExportFormat) => {
      try {
        await exportImportRun(runId, format)
      } catch (e) {
        fail(e)
      }
    }
    const right = { className: 'text-right tabular-nums' }
    return [
      { id: 'title', header: t('imports.runs.import'), cell: ({ row }) => row.original.title },
      {
        id: 'imported',
        header: t('imports.runs.imported'),
        cell: ({ row: { original: run } }) => (
          <span className="flex flex-col">
            <span>{run.importedTime ? formatDateTime(run.importedTime) : ''}</span>
            <span className="text-muted-foreground text-xs">{run.importedBy}</span>
          </span>
        ),
      },
      {
        id: 'outcome',
        header: t('imports.runs.outcome'),
        cell: ({ row: { original: run } }) => (
          <Badge variant={run.outcome === 'committed' ? 'success' : 'destructive'} data-testid={`run-outcome-${run.runId}`}>
            {t(`imports.runs.outcomes.${run.outcome}`)}
          </Badge>
        ),
      },
      { id: 'rows', header: t('imports.rows'), meta: right, cell: ({ row }) => row.original.rows },
      { id: 'duplicates', header: t('imports.duplicates'), meta: right, cell: ({ row }) => row.original.duplicates },
      { id: 'issues', header: t('imports.runs.problems'), meta: right, cell: ({ row }) => row.original.issueCount },
      {
        id: 'notes',
        header: t('imports.notes'),
        meta: { className: 'max-w-xs' },
        cell: ({ row }) => (
          <span className="block truncate" title={row.original.notes ?? undefined}>
            {row.original.notes}
          </span>
        ),
      },
      {
        id: 'actions',
        header: () => <span className="sr-only">{t('list.actions')}</span>,
        meta: { pinned: 'right' },
        cell: ({ row: { original: run } }) => (
          <span className="flex items-center gap-1">
            <Button variant="outline" size="sm" onClick={() => void open(run.runId!)} data-testid={`run-open-${run.runId}`}>
              {t('imports.runs.details')}
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" data-testid={`run-save-${run.runId}`}>
                  {t('reports.export')}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                {FORMATS.map((format) => (
                  <DropdownMenuItem key={format} onSelect={() => void save(run.runId!, format)}>
                    {t(`reports.formats.${format}`)}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          </span>
        ),
      },
    ]
  }, [t])

  const shown = detail?.run
  const entry = catalog.data?.find((i) => i.id === shown?.run?.importId)
  return (
    <AdminPage
      title={t('imports.runs.title')}
      breadcrumb={[{ label: t('nav.imports'), to: '/imports' }, { label: t('imports.runs.title') }]}
      description={importId && <code className="font-mono text-xs">{importId}</code>}
    >
      <div data-testid="import-runs">
        <DataTable
          label={t('imports.runs.title')}
          columns={columns}
          getRowId={(run) => run.runId ?? ''}
          loading={runs.isLoading}
          {...page.paging}
          data={page.data}
        />
      </div>
      <Sheet open={detail?.open ?? false} onOpenChange={(open) => !open && setDetail((d) => d && { ...d, open: false })}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-3xl" data-testid="import-run-detail">
          <SheetHeader>
            <SheetTitle>{shown?.run?.title}</SheetTitle>
            <SheetDescription className="sr-only">{t('imports.runs.details')}</SheetDescription>
          </SheetHeader>
          {shown && (
            <div className="flex flex-col gap-4 px-4 pb-6">
              <DescriptionList
                items={[
                  { key: 'notes', label: t('imports.notes'), value: shown.run?.notes || '—' },
                  ...(shown.run?.sha256
                    ? [{ key: 'sha256', label: 'SHA-256', value: <code className="font-mono text-xs">{shown.run.sha256}</code>, copy: shown.run.sha256 }]
                    : []),
                ]}
              />
              <IssueTable entry={entry} issues={shown.issues ?? []} />
            </div>
          )}
        </SheetContent>
      </Sheet>
    </AdminPage>
  )
}
