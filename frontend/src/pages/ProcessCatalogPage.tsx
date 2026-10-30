import { Badge, DataTable, type ColumnDef } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ListSearch } from '../components/ListSearch'
import { useLocalList } from '../components/useLocalList'
import AdminPage from '../layout/AdminPage'
import { useProcesses } from '../meta/hooks'
import type { ProcessEntry } from '../meta/types'
import { paths } from './paths'

/** The processes the user may run (GET /api/meta/processes); each opens a form generated from its input type. */
export default function ProcessCatalogPage() {
  const { t, i18n } = useTranslation()
  const processes = useProcesses()
  // Phase 17w: searched by name, id and description, sorted by name, id or version; by name at first.
  const list = useLocalList(processes.data, {
    text: (p) => [p.label, p.name, p.description],
    sortBy: { label: (p) => p.label, name: (p) => p.name, version: (p) => p.version },
    defaultSort: [{ id: 'label', desc: false }],
    locale: i18n.language,
  })

  const columns: ColumnDef<ProcessEntry, unknown>[] = [
    {
      id: 'label',
      accessorFn: (p) => p.label,
      header: t('catalog.processesTitle'),
      cell: ({ row: { original: p } }) => (
        <Link
          to={paths.process(p.name, p.version)}
          data-testid={`process-${p.name}-${p.version}`}
          className="text-primary font-medium hover:underline"
        >
          {p.label}
        </Link>
      ),
    },
    { id: 'name', accessorFn: (p) => p.name, header: 'ID', cell: ({ row }) => <code className="font-mono text-xs">{row.original.name}</code> },
    {
      id: 'version',
      accessorFn: (p) => p.version,
      header: t('catalog.version'),
      cell: ({ row: { original: p } }) => (
        <span className="inline-flex items-center gap-1">
          {p.version}
          {p.latest && <Badge variant="success">{t('catalog.latest')}</Badge>}
          {p.deprecated && <Badge variant="warning">{t('catalog.deprecated')}</Badge>}
        </span>
      ),
    },
    {
      id: 'description',
      enableSorting: false,
      header: () => <span className="sr-only">{t('catalog.description')}</span>,
      meta: { className: 'max-w-md' },
      cell: ({ row: { original: p } }) => (
        <span className="text-muted-foreground block truncate" title={p.description ?? undefined}>
          {p.description}
        </span>
      ),
    },
  ]

  return (
    <AdminPage title={t('catalog.processesTitle')}>
      <ListSearch value={list.query} onChange={list.setQuery} matched={list.matched} total={list.total} />
      <DataTable
        label={t('catalog.processesTitle')}
        columns={columns}
        getRowId={(p) => `${p.name}@${p.version}`}
        loading={processes.isLoading}
        empty={list.total > 0 ? t('list.noMatch') : t('catalog.empty')}
        {...list.table}
      />
    </AdminPage>
  )
}
