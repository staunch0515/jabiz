import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, formatAmount, formatDate } from '@jabiz/admin'
import { Input, Space, Table, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { type Asset, loadAssets } from './api'
import { assetPath } from './paths'
import { AssetStatusTag } from './StatusTag'

const money = (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 })
const message = (e: unknown) => (e instanceof ApiError ? e.display : String(e))

/** The assets of the register (FIN-FA-002), each opening its page with its schedule. */
export default function AssetListPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [search, setSearch] = useState('')
  const assets = useQuery({ queryKey: ['fin', 'fa', 'assets'], queryFn: loadAssets })
  const needle = search.trim().toLowerCase()
  const shown = (assets.data ?? []).filter((a) => !needle || a.assetNo.toLowerCase().includes(needle)
    || a.description.toLowerCase().includes(needle) || (a.classCode ?? '').toLowerCase().includes(needle))

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{t('fa.assetsTitle')}</Typography.Title>
      <Input.Search aria-label={t('fa.search')} placeholder={t('fa.search')} allowClear style={{ width: 320 }}
        value={search} onChange={(e) => setSearch(e.target.value)} />
      <Table<Asset>
        data-testid="asset-table"
        size="small"
        rowKey="assetId"
        loading={assets.isLoading}
        dataSource={shown}
        pagination={{ pageSize: 50, showSizeChanger: false }}
        locale={{ emptyText: assets.error ? message(assets.error) : t('fa.noAssets') }}
        columns={[
          { title: t('fa.assetNo'), dataIndex: 'assetNo',
            render: (v: string, row) => <Link to={assetPath(row.assetId)}>{v}</Link> },
          { title: t('fa.description'), dataIndex: 'description' },
          { title: t('fa.class'), dataIndex: 'classCode' },
          { title: t('fa.inService'), dataIndex: 'inServiceDate', render: (v: string) => formatDate(v) },
          { title: t('fa.cost'), dataIndex: 'cost', align: 'right', render: money },
          { title: t('fa.accumulated'), dataIndex: 'accumulated', align: 'right', render: money },
          { title: t('fa.netBookValue'), key: 'nbv', align: 'right',
            render: (_: unknown, row) => row.status === 'DISPOSED' || !row.active ? '—'
              : money(Number(row.cost) - Number(row.accumulated ?? 0)) },
          { title: t('fa.status'), dataIndex: 'status',
            render: (v: Asset['status'], row) => <AssetStatusTag status={v} active={row.active}
              classified={Boolean(row.classCode)} /> },
        ]}
      />
    </Space>
  )
}
