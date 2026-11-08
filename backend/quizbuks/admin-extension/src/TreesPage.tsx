import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { DataTable, EXTENSION_NAMESPACE, PageHeader, UserName, useLanguage, type ColumnDef } from '@jabiz/admin'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PATH } from './ids'
import { useOperatorTrees } from './trees-hooks'
import { i18nText, readTrees, TREES_KEY, type TreeRow } from './trees-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { submittedAt: 'submittedAt' } as const

/** Who owns a map: the platform, an operator, or a Sponsor (by name). */
export function TreeOwner({ tree }: { tree: Pick<TreeRow, 'ownerKind' | 'ownerId'> }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const ownerLabel = useLabels(DICTIONARY.treeOwner)
  if (tree.ownerKind === 'PLATFORM' || !tree.ownerId) return <>{t('trees.ownerPlatform')}</>
  if (tree.ownerKind === 'SPONSOR') return <UserName id={tree.ownerId} />
  return <>{ownerLabel(tree.ownerKind)}</>
}

/**
 * The knowledge maps (F-S2b3a): the platform's templates, the operators' copies and the Sponsors' maps, each with its
 * version in effect and the one being edited or reviewed, the waiting longest first once sorted by submission. An
 * operator's member lists its own operator's and its Sponsors' (the server's template); each opens on its review.
 */
export default function TreesPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const language = useLanguage()
  const operator = useOperatorTrees()
  const statusLabel = useLabels(DICTIONARY.treeStatus)
  const versionLabel = useLabels(DICTIONARY.treeVersionStatus)
  const subjectLabel = useLabels(DICTIONARY.treeSubject)
  const { query, table, sortable } = usePaged([...TREES_KEY, operator],
    (offset, limit, sorts) => readTrees(operator, offset, limit, sorts), true, SORTS)
  const columns = useMemo<ColumnDef<TreeRow, unknown>[]>(() => [
    { id: 'name', header: t('trees.columns.name'), cell: ({ row }) => {
      const name = i18nText(row.original.name, language) || t('trees.unnamed')
      return (
        <Link to={PATH.tree(row.original.treeId)} className={linkClass} aria-label={t('trees.open', { name })}
          data-testid="tree-link">{name}</Link>
      )
    } },
    { id: 'owner', header: t('trees.columns.owner'), cell: ({ row }) => <TreeOwner tree={row.original} /> },
    { id: 'subject', header: t('trees.columns.subject'), cell: ({ row }) => subjectLabel(row.original.subjectKind) },
    { id: 'status', header: t('trees.columns.status'), cell: ({ row }) => (
      <StatusBadge code={row.original.status} label={statusLabel(row.original.status)} />
    ) },
    { id: 'version', header: t('trees.columns.version'), cell: ({ row }) => (row.original.versionNo == null
      ? t('trees.noneInEffect') : t('trees.versionNo', { n: row.original.versionNo })) },
    { id: 'open', header: t('trees.columns.open'), cell: ({ row }) => (row.original.openStatus ? (
      <span className="flex flex-wrap items-center gap-2">
        {t('trees.versionNo', { n: row.original.openVersionNo })}
        <StatusBadge code={row.original.openStatus} label={versionLabel(row.original.openStatus)} />
      </span>
    ) : '') },
    { id: 'submittedAt', header: t('trees.columns.submittedAt'), cell: ({ row }) => when(row.original.submittedAt) },
    { id: 'nodes', header: t('trees.columns.nodes'), cell: ({ row }) => row.original.nodes },
    { id: 'lit', header: t('trees.columns.lit'), cell: ({ row }) => row.original.litSubjects },
  ], [t, language, statusLabel, versionLabel, subjectLabel])
  return (
    <div className="flex flex-col gap-4" data-testid="qb-trees">
      <PageHeader title={t('trees.title')} className="pb-0"
        description={operator ? t('trees.operatorDescription') : t('trees.description')} />
      {query.isError ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} /> : (
        <DataTable label={t('trees.table')} columns={sortable(columns)} {...table}
          getRowId={(row) => row.treeId} empty={<EmptyState art="search" title={t('trees.none')} />}
          rowProps={(row) => ({ 'data-testid': 'tree-row', 'data-id': row.treeId })} />
      )}
    </div>
  )
}
