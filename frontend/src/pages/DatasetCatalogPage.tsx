import { Badge, CopyButton, DataTable, type ColumnDef } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { useLocalPage } from '../components/useLocalPage'
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
  const { t } = useTranslation()
  const datasets = useDatasets()
  const page = useLocalPage(datasets.data)

  const columns: ColumnDef<DatasetEntry, unknown>[] = [
    {
      id: 'label',
      header: t('catalog.entity'),
      cell: ({ row: { original: d } }) => (
        <Link to={paths.dataset(d.id)} data-testid={datasetTestId(d)} className="text-primary font-medium hover:underline">
          {d.label}
        </Link>
      ),
    },
    {
      id: 'id',
      header: t('catalog.id'),
      cell: ({ row: { original: d } }) => (
        <span className="inline-flex items-center gap-1">
          <code className="text-muted-foreground font-mono text-xs">{d.id}</code>
          <CopyButton value={d.id} label={`${t('copy.copy', { ns: 'ui' })} ${d.id}`} />
        </span>
      ),
    },
    {
      id: 'traits',
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
      <DataTable
        label={t('catalog.datasetsTitle')}
        columns={columns}
        getRowId={(d) => d.id}
        loading={datasets.isLoading}
        empty={t('catalog.empty')}
        {...page.paging}
        data={page.data}
      />
    </AdminPage>
  )
}
