import { useQuery } from '@tanstack/react-query'
import { Table, Tag, Tooltip } from 'antd'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import type { components } from '@jabiz/client'
import { formatDateTime } from '../meta/format'

type Delivery = components['schemas']['DocumentDeliveryEntry']

const COLORS: Record<string, string> = { SENT: 'green', FAILED: 'red', PENDING: 'default' }

/**
 * Every address an issued document was sent to and how it went (docs/design/22-documents.md section 5): sent, waiting
 * for the first attempt, or failed so far (the platform retries; the last error is shown on hover).
 */
export default function DocumentDeliveries({ runId }: { runId: string }) {
  const { t } = useTranslation()
  const detail = useQuery({
    queryKey: ['document', runId],
    queryFn: () => unwrap(api.GET('/api/documents/runs/{runId}', { params: { path: { runId } } })),
  })
  return (
    <Table<Delivery>
      size="small"
      rowKey="deliveryId"
      loading={detail.isLoading}
      pagination={false}
      dataSource={detail.data?.deliveries ?? []}
      locale={{ emptyText: t('documents.noDeliveries') }}
      data-testid={`document-deliveries-${runId}`}
      columns={[
        { title: t('documents.delivery.address'), dataIndex: 'address' },
        {
          title: t('documents.delivery.outcome'),
          dataIndex: 'outcome',
          render: (_, delivery) => (
            <Tooltip title={delivery.lastError}>
              <Tag color={COLORS[delivery.outcome ?? 'PENDING']} data-testid={`delivery-${delivery.deliveryId}`}>
                {t(`documents.outcome.${delivery.outcome ?? 'PENDING'}`)}
              </Tag>
            </Tooltip>
          ),
        },
        { title: t('documents.delivery.attempts'), dataIndex: 'attempts', align: 'right' },
        {
          title: t('documents.delivery.requested'),
          dataIndex: 'createdTime',
          render: (_, delivery) =>
            `${delivery.createdTime ? formatDateTime(delivery.createdTime) : ''} · ${delivery.requestedBy ?? ''}`,
        },
      ]}
    />
  )
}
