import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, formatDate } from '@jabiz/admin'
import { Alert, Descriptions, Space, Table, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { LIMIT, runLineDetail, sum, type DetailLine } from './api'
import { documentPath, figure } from './format'

const message = (e: unknown) => (e instanceof ApiError ? e.display : String(e))

interface AccountTotal {
  accountCode: string
  accountName: string
  total: string
}

/**
 * What makes a statement figure (FIN-RP-006, FIN-UI-004): the accounts behind it with their totals, then their entry
 * lines (a balance's opening first) with the same day, span and knownAt as the statement; each document opens its
 * page, with its attachments. The lines add up to the figure in the statement's own sign convention reversed where
 * the layout shows credits positive.
 */
export default function LineDetailPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [search] = useSearchParams()
  const { label, ...params } = Object.fromEntries(search.entries())
  const lines = useQuery({
    queryKey: ['fin', 'line-detail', params],
    queryFn: () => runLineDetail(params),
    enabled: Boolean(params.accounts && params.through),
  })
  const items = lines.data?.items ?? []
  const accounts: AccountTotal[] = [...new Set(items.map((l) => l.accountCode))].map((code) => ({
    accountCode: code,
    accountName: items.find((l) => l.accountCode === code)?.accountName ?? '',
    total: sum(items.filter((l) => l.accountCode === code).map((l) => l.amount)),
  }))

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">
        {label ? t('reports.detailOf', { label }) : t('reports.detail')}
      </Typography.Title>
      <Descriptions size="small" column={4}>
        <Descriptions.Item label={t('reports.accounts')}>{params.accounts}</Descriptions.Item>
        <Descriptions.Item label={t('reports.param.through')}>{params.through && formatDate(params.through)}
        </Descriptions.Item>
        <Descriptions.Item label={t('reports.span')}>{t(`reports.spans.${params.span ?? 'BALANCE'}`)}
        </Descriptions.Item>
        {params.knownAt && <Descriptions.Item label={t('reports.param.knownAt')}>{params.knownAt}
        </Descriptions.Item>}
      </Descriptions>
      {items.length >= LIMIT && <Alert type="warning" showIcon message={t('reports.cut', { limit: LIMIT })} />}
      <Table<AccountTotal>
        data-testid="accounts"
        size="small"
        rowKey="accountCode"
        loading={lines.isLoading}
        dataSource={accounts}
        pagination={false}
        locale={{ emptyText: lines.error ? message(lines.error) : t('reports.noLines') }}
        summary={() => accounts.length > 1 ? (
          <Table.Summary.Row>
            <Table.Summary.Cell index={0} colSpan={2}>{t('reports.total')}</Table.Summary.Cell>
            <Table.Summary.Cell index={2} align="right">
              <span data-testid="detail-total">{figure(sum(accounts.map((a) => a.total)))}</span>
            </Table.Summary.Cell>
          </Table.Summary.Row>
        ) : undefined}
        columns={[
          { title: t('reports.account'), dataIndex: 'accountCode' },
          { title: t('reports.accountName'), dataIndex: 'accountName' },
          { title: t('reports.amount'), dataIndex: 'total', align: 'right',
            render: (v: string, a) => <span data-testid={`account-${a.accountCode}`}>{figure(v)}</span> },
        ]}
      />
      <Table<DetailLine>
        data-testid="lines"
        size="small"
        rowKey="lineKey"
        loading={lines.isLoading}
        dataSource={items}
        pagination={{ pageSize: 100, showSizeChanger: false, hideOnSinglePage: true }}
        columns={[
          { title: t('reports.account'), dataIndex: 'accountCode' },
          { title: t('reports.postingDate'), dataIndex: 'postingDate',
            render: (v: string | null, l) => l.kind === 'OPENING' ? t('reports.opening') : v && formatDate(v) },
          { title: t('reports.glNo'), dataIndex: 'glNo' },
          {
            title: t('reports.document'), dataIndex: 'documentNo',
            render: (v: string | null, l) => {
              const path = documentPath(l)
              return path ? <Link to={path} data-testid={`document-${l.lineKey}`}>{v}</Link> : v
            },
          },
          { title: t('reports.description'), dataIndex: 'description' },
          { title: t('reports.memo'), dataIndex: 'memo' },
          { title: t('reports.amount'), dataIndex: 'amount', align: 'right', render: figure },
        ]}
      />
    </Space>
  )
}
