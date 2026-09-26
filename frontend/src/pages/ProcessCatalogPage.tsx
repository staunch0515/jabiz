import { PageContainer, ProTable, type ProColumns } from '@ant-design/pro-components'
import { Space, Tag } from 'antd'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { useProcesses } from '../meta/hooks'
import type { ProcessEntry } from '../meta/types'
import { paths } from './paths'

/** The processes the user may run (GET /api/meta/processes); each opens a form generated from its input type. */
export default function ProcessCatalogPage() {
  const { t } = useTranslation()
  const processes = useProcesses()

  const columns: ProColumns<ProcessEntry>[] = [
    {
      title: t('catalog.processesTitle'),
      dataIndex: 'label',
      render: (_, p) => (
        <Link to={paths.process(p.name, p.version)} data-testid={`process-${p.name}-${p.version}`}>
          {p.label}
        </Link>
      ),
    },
    { title: 'ID', dataIndex: 'name' },
    {
      title: t('catalog.version'),
      dataIndex: 'version',
      render: (_, p) => (
        <Space size={4}>
          {p.version}
          {p.latest && <Tag color="green">{t('catalog.latest')}</Tag>}
          {p.deprecated && <Tag color="orange">{t('catalog.deprecated')}</Tag>}
        </Space>
      ),
    },
    { title: '', dataIndex: 'description', ellipsis: true },
  ]

  return (
    <PageContainer title={t('catalog.processesTitle')}>
      <ProTable<ProcessEntry>
        rowKey={(p) => `${p.name}@${p.version}`}
        search={false}
        options={false}
        loading={processes.isLoading}
        dataSource={processes.data}
        columns={columns}
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        locale={{ emptyText: t('catalog.empty') }}
      />
    </PageContainer>
  )
}
