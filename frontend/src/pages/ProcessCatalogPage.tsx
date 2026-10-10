import { Badge, DataTable, type ColumnDef } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { useLocalPage } from '../components/useLocalPage'
import AdminPage from '../layout/AdminPage'
import { useProcesses } from '../meta/hooks'
import type { ProcessEntry } from '../meta/types'
import { paths } from './paths'

/** The processes the user may run (GET /api/meta/processes); each opens a form generated from its input type. */
export default function ProcessCatalogPage() {
  const { t } = useTranslation()
  const processes = useProcesses()
  const page = useLocalPage(processes.data)

  const columns: ColumnDef<ProcessEntry, unknown>[] = [
    {
      id: 'label',
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
    { id: 'name', header: 'ID', cell: ({ row }) => <code className="font-mono text-xs">{row.original.name}</code> },
    {
      id: 'version',
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
      <DataTable
        label={t('catalog.processesTitle')}
        columns={columns}
        getRowId={(p) => `${p.name}@${p.version}`}
        loading={processes.isLoading}
        empty={t('catalog.empty')}
        {...page.paging}
        data={page.data}
      />
    </AdminPage>
  )
}
