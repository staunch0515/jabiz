import { Badge, CopyButton, DataTable, UI_NAMESPACE, type ColumnDef } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ListSearch } from '../components/ListSearch'
import { useLocalList } from '../components/useLocalList'
import AdminPage from '../layout/AdminPage'
import { useDatasets } from '../meta/hooks'
import { datasetTestId } from '../meta/references'
import type { DatasetEntry } from '../meta/types'
import { paths } from './paths'

/**
 * Every dataset the user may read (GET /api/meta/datasets). A new entity with a dataset appears here without any
 * frontend code, whether or not a menu item points at it.
 */
export default function DatasetCatalogPage() {
  const { t, i18n } = useTranslation()
  const datasets = useDatasets()
  // Phase 17w: searched by name and id, sorted by either; by name at first.
  const list = useLocalList(datasets.data, {
    text: (d) => [d.label, d.id],
    sortBy: { label: (d) => d.label, id: (d) => d.id },
    defaultSort: [{ id: 'label', desc: false }],
    locale: i18n.language,
  })

  const columns: ColumnDef<DatasetEntry, unknown>[] = [
    {
      id: 'label',
      accessorFn: (d) => d.label,
      header: t('catalog.entity'),
      cell: ({ row: { original: d } }) => (
        <Link to={paths.dataset(d.id)} data-testid={datasetTestId(d)} className="text-primary font-medium hover:underline">
          {d.label}
        </Link>
      ),
    },
    {
      id: 'id',
      accessorFn: (d) => d.id,
      header: t('catalog.id'),
      cell: ({ row: { original: d } }) => (
        <span className="inline-flex items-center gap-1">
          <code className="text-muted-foreground font-mono text-xs">{d.id}</code>
          <CopyButton value={d.id} label={t('copy.copyValue', { ns: UI_NAMESPACE, value: d.id })} />
        </span>
      ),
    },
    {
      id: 'traits',
      enableSorting: false,
      header: () => <span className="sr-only">{t('catalog.traits')}</span>,
      cell: ({ row: { original: d } }) => (
        <span className="flex flex-wrap gap-1">
          {d.temporal && <Badge variant="secondary">{t('catalog.temporal')}</Badge>}
          {d.readOnly && <Badge variant="outline">{t('catalog.readOnly')}</Badge>}
          {d.processOnlyWrites && <Badge variant="outline">{t('catalog.processOnly')}</Badge>}
          {d.canWrite && <Badge variant="success">{t('catalog.writable')}</Badge>}
        </span>
      ),
    },
  ]

  return (
    <AdminPage title={t('catalog.datasetsTitle')}>
      <ListSearch value={list.query} onChange={list.setQuery} matched={list.matched} total={list.total} />
      <DataTable
        label={t('catalog.datasetsTitle')}
        columns={columns}
        getRowId={(d) => d.id}
        loading={datasets.isLoading}
        empty={list.total > 0 ? t('list.noMatch') : t('catalog.empty')}
        {...list.table}
      />
    </AdminPage>
  )
}
