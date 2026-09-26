import { PageContainer, ProTable, type ProColumns } from '@ant-design/pro-components'
import { Space, Tag } from 'antd'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { useDatasets } from '../meta/hooks'
import type { DatasetEntry } from '../meta/types'
import { paths } from './paths'

/**
 * Every dataset the user may read (GET /api/meta/datasets). A new entity with a dataset appears here without any
 * frontend code, whether or not a menu item points at it.
 */
export default function DatasetCatalogPage() {
  const { t } = useTranslation()
  const datasets = useDatasets()

  const columns: ProColumns<DatasetEntry>[] = [
    {
      title: t('catalog.entity'),
      dataIndex: 'label',
      render: (_, d) => (
        <Link to={paths.dataset(d.id)} data-testid={`dataset-${d.entity}`}>
          {d.label}
        </Link>
      ),
    },
    { title: t('catalog.id'), dataIndex: 'id', copyable: true },
    {
      title: '',
      key: 'traits',
      render: (_, d) => (
        <Space size={4} wrap>
          {d.temporal && <Tag color="blue">{t('catalog.temporal')}</Tag>}
          {d.readOnly && <Tag>{t('catalog.readOnly')}</Tag>}
          {d.processOnlyWrites && <Tag color="purple">{t('catalog.processOnly')}</Tag>}
          {d.canWrite && <Tag color="green">{t('catalog.writable')}</Tag>}
        </Space>
      ),
    },
  ]

  return (
    <PageContainer title={t('catalog.datasetsTitle')}>
      <ProTable<DatasetEntry>
        rowKey="id"
        search={false}
        options={false}
        loading={datasets.isLoading}
        dataSource={datasets.data}
        columns={columns}
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        locale={{ emptyText: t('catalog.empty') }}
      />
    </PageContainer>
  )
}
