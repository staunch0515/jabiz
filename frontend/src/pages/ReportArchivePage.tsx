import { api, unwrap, ApiError, UserName, formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  CopyButton,
  DataTable,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  notify,
  Tooltip,
  TooltipContent,
  TooltipTrigger,
  UI_NAMESPACE,
  type ColumnDef,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { exportRun, type ExportFormat } from '../api/reports'
import type { components } from '@jabiz/client'
import { ListSearch } from '../components/ListSearch'
import { useLocalList } from '../components/useLocalList'
import AdminPage from '../layout/AdminPage'

type RunSummary = components['schemas']['RunSummary']
type Verification = components['schemas']['Verification']

const FORMATS: ExportFormat[] = ['pdf', 'xlsx', 'csv']
const LINK = 'h-auto px-1'

/**
 * The verdicts of the runs verified on this page. A cell reads them from here rather than from the column
 * definitions: the table mounts its cells anew when its columns change, which would take focus from the "Verify"
 * button the user just pressed.
 */
const VerdictsContext = createContext<Record<string, Verification>>({})

/** A run's verdict, once verified. */
function VerdictBadge({ runId }: { runId: string }) {
  const { t } = useTranslation()
  const verdict = useContext(VerdictsContext)[runId]
  if (!verdict) return null
  return (
    <Badge variant={verdict.verdict === 'identical' ? 'success' : 'destructive'} data-testid={`verdict-${runId}`}>
      {t(`reports.archive.verdict.${verdict.verdict}`)}
    </Badge>
  )
}

/**
 * Issued reports (docs/design/19-reports.md section 5): newest first, optionally of one template (`?template=`).
 * Each can be saved again exactly as issued, and verified against the data at its point in time. The server lists
 * only runs the user may read.
 */
export default function ReportArchivePage() {
  const { t, i18n } = useTranslation()
  const [searchParams] = useSearchParams()
  const template = searchParams.get('template') ?? undefined
  const [verdicts, setVerdicts] = useState<Record<string, Verification>>({})
  const runs = useQuery({
    queryKey: ['report-runs', template],
    queryFn: () => unwrap(api.GET('/api/reports/runs', { params: { query: { template, limit: 200 } } })),
  })
  // Phase 17w: the latest runs searched and sorted here, newest first at first.
  const list = useLocalList(runs.data, {
    text: (run) => [run.title, run.period, run.issuedBy, run.contentHash],
    sortBy: { title: (run) => run.title, issued: (run) => run.issuedTime, rows: (run) => run.rowCount },
    defaultSort: [{ id: 'issued', desc: true }],
    locale: i18n.language,
  })
  useEffect(() => {
    if (runs.error) notify.error(runs.error instanceof ApiError ? runs.error.display : String(runs.error))
  }, [runs.error])

  // Stable while verdicts arrive (they come through VerdictsContext), so no cell is mounted anew.
  const columns = useMemo<ColumnDef<RunSummary, unknown>[]>(() => {
    const verify = async (runId: string) => {
      try {
        const result = await unwrap(api.POST('/api/reports/runs/{runId}/verify', { params: { path: { runId } } }))
        setVerdicts((current) => ({ ...current, [runId]: result }))
      } catch (e) {
        notify.error(e instanceof ApiError ? e.display : String(e))
      }
    }

    const save = async (runId: string, format: ExportFormat) => {
      try {
        await exportRun(runId, format)
      } catch (e) {
        notify.error(e instanceof ApiError ? e.display : String(e))
      }
    }
    return [
      {
        id: 'title',
        accessorFn: (run) => run.title,
        header: t('reports.archive.report'),
        cell: ({ row: { original: run } }) => (
          <span className="flex flex-col">
            <span>{run.title}</span>
            <span className="text-muted-foreground text-xs">{run.period}</span>
          </span>
        ),
      },
      {
        id: 'issued',
        accessorFn: (run) => run.issuedTime,
        header: t('reports.archive.issued'),
        cell: ({ row: { original: run } }) => (
          <span className="flex flex-col">
            <span>{run.issuedTime ? formatDateTime(run.issuedTime) : ''}</span>
            {/* An issuer shown by id breaks at its hyphens rather than widen the table (see DocumentsPage). */}
            <span className="text-muted-foreground inline-block max-w-44 text-xs whitespace-normal">
              <UserName id={run.issuedBy} />
            </span>
          </span>
        ),
      },
      {
        id: 'rows',
        accessorFn: (run) => run.rowCount,
        header: t('reports.archive.rows'),
        meta: { className: 'text-right tabular-nums' },
        cell: ({ row }) => row.original.rowCount,
      },
      {
        id: 'hash',
        header: t('reports.archive.hash'),
        cell: ({ row: { original: run } }) =>
          run.contentHash && (
            <span className="inline-flex items-center gap-1 whitespace-nowrap">
              <code className="font-mono text-xs" title={run.contentHash}>
                {run.contentHash.slice(0, 12)}
              </code>
              <CopyButton
                value={run.contentHash}
                label={t('copy.copyValue', { ns: UI_NAMESPACE, value: t('reports.archive.hash') })}
              />
            </span>
          ),
      },
      {
        id: 'status',
        header: t('reports.archive.status'),
        cell: ({ row: { original: run } }) => (
          <span className="flex flex-wrap items-center gap-1">
            {run.supersededBy && <Badge variant="secondary">{t('reports.archive.superseded')}</Badge>}
            {!run.recomputable && (
              <Tooltip>
                <TooltipTrigger asChild>
                  {/* A button, so the hint is reached with the keyboard too (focus opens the tooltip). */}
                  <button type="button" className="focus-visible:ring-ring/50 rounded-md outline-none focus-visible:ring-[3px]">
                    <Badge variant="outline">{t('reports.archive.notRecomputable')}</Badge>
                  </button>
                </TooltipTrigger>
                <TooltipContent>{t('reports.archive.notRecomputableHint')}</TooltipContent>
              </Tooltip>
            )}
            <VerdictBadge runId={run.runId ?? ''} />
          </span>
        ),
      },
      {
        id: 'actions',
        header: t('list.actions'),
        meta: { pinned: 'right' },
        cell: ({ row: { original: run } }) => (
          <span className="flex flex-wrap items-center gap-1">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="link" size="sm" className={LINK} data-testid={`run-save-${run.runId}`}>
                  {t('reports.archive.save')}
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
            <Button
              variant="link"
              size="sm"
              className={LINK}
              onClick={() => void verify(run.runId!)}
              data-testid={`run-verify-${run.runId}`}
            >
              {t('reports.archive.verify')}
            </Button>
          </span>
        ),
      },
    ]
  }, [t])

  return (
    <AdminPage
      title={t('reports.archive.title')}
      breadcrumb={[{ label: t('nav.reports'), to: '/reports' }, { label: t('reports.archive.title') }]}
      description={template && <code className="font-mono text-xs">{template}</code>}
    >
      <div className="flex flex-col gap-4" data-testid="report-archive">
        <ListSearch value={list.query} onChange={list.setQuery} matched={list.matched} total={list.total} />
        <VerdictsContext.Provider value={verdicts}>
          <DataTable
            label={t('reports.archive.title')}
            columns={columns}
            {...list.table}
            getRowId={(run) => run.runId ?? ''}
            loading={runs.isLoading}
          />
        </VerdictsContext.Provider>
      </div>
    </AdminPage>
  )
}
