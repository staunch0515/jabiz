import { Descriptions, Drawer, Spin, Table } from 'antd'
import { useQuery } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import UserName from './UserName'

interface Operation {
  processSeqId: number
  parentSeqId: number | null
  revertsSeqId: number | null
  processName: string
  processVersion: number
  actorId: string
  reason: string | null
  opTime: string
  items: Record<string, unknown>[]
  children: number[]
}

/** An operation with the versions it wrote (GET /api/processes/executions/{seq}, permission operation.read). */
export default function OperationDrawer({ seq, onClose }: { seq: number | null; onClose(): void }) {
  const { t } = useTranslation()
  const operation = useQuery({
    queryKey: ['operation', seq],
    enabled: seq != null,
    queryFn: async () =>
      (await unwrap(
        api.GET('/api/processes/executions/{processSeqId}', { params: { path: { processSeqId: seq! } } }),
      )) as unknown as Operation,
  })
  const data = operation.data
  const itemKeys = data?.items?.length ? Object.keys(data.items[0]) : []
  return (
    <Drawer open={seq != null} onClose={onClose} width={720} title={t('history.operation') + (seq ? ` #${seq}` : '')}>
      {operation.isLoading && <Spin />}
      {operation.error && <div>{operation.error instanceof ApiError ? operation.error.display : String(operation.error)}</div>}
      {data && (
        <>
          <Descriptions column={1} bordered size="small" data-testid="operation-detail">
            <Descriptions.Item label="#">{data.processSeqId}</Descriptions.Item>
            <Descriptions.Item label={t('history.process')}>
              {data.processName} v{data.processVersion}
            </Descriptions.Item>
            <Descriptions.Item label={t('history.by')}><UserName id={data.actorId} /></Descriptions.Item>
            <Descriptions.Item label={t('history.recorded')}>{dayjs(data.opTime).format('YYYY-MM-DD HH:mm:ss')}</Descriptions.Item>
            <Descriptions.Item label={t('history.reason')}>{data.reason ?? '—'}</Descriptions.Item>
            {data.parentSeqId != null && <Descriptions.Item label="parent">#{data.parentSeqId}</Descriptions.Item>}
            {data.revertsSeqId != null && <Descriptions.Item label={t('history.revert')}>#{data.revertsSeqId}</Descriptions.Item>}
          </Descriptions>
          <h4 style={{ marginTop: 24 }}>{t('history.items')}</h4>
          <Table
            size="small"
            rowKey={(_, i) => String(i)}
            pagination={false}
            dataSource={data.items}
            scroll={{ x: 'max-content' }}
            columns={itemKeys.map((key) => ({
              title: key,
              dataIndex: key,
              render: (value: unknown) => (Array.isArray(value) ? value.join(', ') : String(value ?? '—')),
            }))}
          />
        </>
      )}
    </Drawer>
  )
}
