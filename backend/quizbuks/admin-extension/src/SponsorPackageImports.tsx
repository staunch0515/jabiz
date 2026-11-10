import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { DataTable, EXTENSION_NAMESPACE, type ColumnDef } from '@jabiz/admin'
import { EmptyState, LoadFailed, Section } from './components'
import { useLabels, usePaged, when } from './hooks'
import { DICTIONARY } from './ids'
import { readPackageImports, type PackageImportRow } from './package-api'

const right = { className: 'text-right tabular-nums' }

/**
 * A Sponsor's imports of sponsor packages, read only (F-C4, docs/quizbuks/plans/impl/F-C4.md): for support and the
 * Sponsor's operator to look into an import that went wrong: how it imported, its state, how far it got and when.
 * Shown to readers of every publication (`qb.admin.publication.read`: support, and an operator's members, whom the
 * server limits to their own Sponsors); there is nothing to change here.
 */
export function SponsorPackageImports({ userId }: { userId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const modeLabel = useLabels(DICTIONARY.packageImportMode)
  const statusLabel = useLabels(DICTIONARY.packageImportStatus)
  const { query, table } = usePaged(['qb-ext', 'sponsor-package-imports', userId],
    (offset, limit) => readPackageImports(userId, offset, limit))
  const columns = useMemo<ColumnDef<PackageImportRow, unknown>[]>(() => [
    { id: 'startedAt', header: t('packageImports.columns.startedAt'), cell: ({ row }) => when(row.original.startedAt) },
    { id: 'mode', header: t('packageImports.columns.mode'), cell: ({ row }) => modeLabel(row.original.mode) },
    { id: 'status', header: t('packageImports.columns.status'), cell: ({ row }) => statusLabel(row.original.status) },
    {
      id: 'steps',
      header: t('packageImports.columns.steps'),
      meta: right,
      cell: ({ row }) => t('packageImports.steps', { done: row.original.stepsDone, total: row.original.stepsTotal }),
    },
    {
      id: 'lastStep',
      header: t('packageImports.columns.lastStep'),
      cell: ({ row }) => <span className="font-mono text-xs break-all">{row.original.lastStep ?? ''}</span>,
    },
    {
      id: 'version',
      header: t('packageImports.columns.version'),
      cell: ({ row }) => t('packageImports.version', { version: row.original.formatVersion }),
    },
    { id: 'finishedAt', header: t('packageImports.columns.finishedAt'), cell: ({ row }) => when(row.original.finishedAt) },
  ], [t, modeLabel, statusLabel])
  return (
    <Section title={t('packageImports.title')} testId="sponsor-package-imports">
      <p className="text-muted-foreground text-sm">{t('packageImports.hint')}</p>
      {query.isError ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} /> : (
        <DataTable label={t('packageImports.table')} columns={columns} {...table} getRowId={(row) => row.importId}
          empty={<EmptyState art="content" title={t('packageImports.none')} />}
          rowProps={(row) => ({ 'data-testid': 'sponsor-package-import', 'data-id': row.importId,
            'data-status': row.status })} />
      )}
    </Section>
  )
}
