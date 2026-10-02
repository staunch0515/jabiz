import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, formatAmount, formatDate, paths, useAuth } from '@jabiz/admin'
import { Alert, Card, Descriptions, Space, Table, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import { DATASETS, loadAsset, loadSchedule, PERMISSIONS, type ProjectedMonth, project, type ScheduleRow } from './api'
import { AssetStatusTag } from './StatusTag'

const money = (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 })
const message = (e: unknown) => (e instanceof ApiError ? e.display : String(e))

/**
 * An asset (FIN-FA-002, 009): what it is and how it is depreciated, the months taken (the runs and a disposal month)
 * and the twelve months ahead by the plan the runs follow. Its changes in estimate, disposal and units used are its
 * actions on the asset register's page.
 */
export default function AssetPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { assetId = '' } = useParams()
  const { can } = useAuth()
  const asset = useQuery({ queryKey: ['fin', 'fa', 'asset', assetId], queryFn: () => loadAsset(assetId) })
  const assetNo = asset.data?.assetNo
  const taken = useQuery({ queryKey: ['fin', 'fa', 'schedule', assetNo], queryFn: () => loadSchedule(assetNo as string),
    enabled: Boolean(assetNo) })
  const going = asset.data?.status === 'IN_SERVICE' && Boolean(asset.data?.classCode) && Boolean(asset.data?.active)
  const ahead = useQuery({ queryKey: ['fin', 'fa', 'ahead', assetId], queryFn: () => project(assetId),
    enabled: going })

  if (asset.error) return <Alert type="error" showIcon message={message(asset.error)} />
  if (asset.isLoading) return null
  const a = asset.data
  if (!a) return <Alert type="warning" showIcon message={t('fa.notFound')} data-testid="asset-missing" />

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">
        {a.assetNo} · {a.description}
      </Typography.Title>
      <Card size="small">
        <Descriptions size="small" column={3} bordered>
          <Descriptions.Item label={t('fa.class')}>{a.classCode ?? t('fa.unclassified')}</Descriptions.Item>
          <Descriptions.Item label={t('fa.costAccount')}>{a.costAccount}</Descriptions.Item>
          <Descriptions.Item label={t('fa.status')}>
            <AssetStatusTag status={a.status} active={a.active} classified={Boolean(a.classCode)} />
          </Descriptions.Item>
          <Descriptions.Item label={t('fa.inService')}>{formatDate(a.inServiceDate)}</Descriptions.Item>
          <Descriptions.Item label={t('fa.method')}>{a.method ?? '—'}</Descriptions.Item>
          <Descriptions.Item label={t('fa.life')}>{a.lifeMonths ?? '—'}</Descriptions.Item>
          <Descriptions.Item label={t('fa.cost')}><span data-testid="asset-cost">{money(a.cost)}</span></Descriptions.Item>
          <Descriptions.Item label={t('fa.salvage')}>{money(a.salvage ?? 0)}</Descriptions.Item>
          <Descriptions.Item label={t('fa.accumulated')}>
            <span data-testid="asset-accumulated">{money(a.accumulated ?? 0)}</span>
          </Descriptions.Item>
          <Descriptions.Item label={t('fa.netBookValue')}>
            <span data-testid="asset-nbv">
              {a.status === 'DISPOSED' || !a.active ? '—' : money(Number(a.cost) - Number(a.accumulated ?? 0))}
            </span>
          </Descriptions.Item>
          <Descriptions.Item label={t('fa.depreciatedThrough')}>{a.depreciatedThrough ?? '—'}</Descriptions.Item>
          <Descriptions.Item label={t('fa.location')}>{a.location ?? '—'}</Descriptions.Item>
        </Descriptions>
        {can(PERMISSIONS.maintain) && (
          <Link to={paths.dataset(DATASETS.asset)} data-testid="asset-actions">{t('fa.actions')}</Link>
        )}
      </Card>
      <Card size="small" title={t('fa.takenTitle')}>
        <Table<ScheduleRow>
          data-testid="taken-table"
          size="small"
          rowKey={(r) => `${r.periodKey}-${r.source}-${r.documentNo ?? ''}`}
          loading={taken.isLoading}
          dataSource={taken.data ?? []}
          pagination={false}
          locale={{ emptyText: taken.error ? message(taken.error) : t('fa.nothingTaken') }}
          columns={[
            { title: t('fa.month'), dataIndex: 'periodKey' },
            { title: t('fa.document'), dataIndex: 'documentNo' },
            { title: t('fa.amount'), dataIndex: 'amount', align: 'right', render: money },
            { title: t('fa.accumulatedAfter'), dataIndex: 'accumulated', align: 'right', render: money },
            { title: t('fa.units'), dataIndex: 'units', align: 'right' },
          ]}
        />
      </Card>
      {going && (
        <Card size="small" title={t('fa.aheadTitle')}>
          {ahead.data?.byUse
            ? <Typography.Text type="secondary" data-testid="by-use">{t('fa.byUse')}</Typography.Text>
            : (
              <Table<ProjectedMonth>
                data-testid="ahead-table"
                size="small"
                rowKey="periodKey"
                loading={ahead.isLoading}
                dataSource={ahead.data?.months ?? []}
                pagination={false}
                locale={{ emptyText: ahead.error ? message(ahead.error) : t('fa.nothingAhead') }}
                columns={[
                  { title: t('fa.month'), dataIndex: 'periodKey' },
                  { title: t('fa.amount'), dataIndex: 'amount', align: 'right', render: money },
                  { title: t('fa.accumulatedAfter'), dataIndex: 'accumulated', align: 'right', render: money },
                  { title: t('fa.netBookValue'), dataIndex: 'netBookValue', align: 'right', render: money },
                ]}
              />
            )}
        </Card>
      )}
    </Space>
  )
}
