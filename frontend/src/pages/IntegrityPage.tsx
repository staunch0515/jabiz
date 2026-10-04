import { PageContainer } from '@ant-design/pro-components'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Card, Descriptions, Drawer, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import type { IntegrityCheckDetail, IntegrityCheckSummary, IntegrityProblem, VerifyOutput } from '../api/integrity'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
import { runProcess } from '../lib/calls'
import { formatDateTime } from '../meta/format'
import UserName from '../components/UserName'

const KIND_COLORS: Record<string, string> = {
  MODIFIED: 'red', MISSING: 'red', CHAIN_BROKEN: 'red', SEAL_ALTERED: 'red', UNPROTECTED: 'orange', OTHER_KEY: 'gold',
}

/** The problems of one verification. */
export function ProblemTable({ problems }: { problems: IntegrityProblem[] }) {
  const { t } = useTranslation()
  return (
    <Table<IntegrityProblem>
      size="small"
      rowKey={(p) => `${p.kind}-${p.sealNo}-${p.table}-${p.key}`}
      dataSource={problems}
      pagination={{ pageSize: 50, hideOnSinglePage: true }}
      data-testid="integrity-problems"
      columns={[
        {
          title: t('integrity.kind'),
          dataIndex: 'kind',
          width: 170,
          render: (kind: string) => <Tag color={KIND_COLORS[kind]}>{t(`integrity.kinds.${kind}`)}</Tag>,
        },
        { title: t('integrity.seal'), dataIndex: 'sealNo', width: 90 },
        { title: t('integrity.table'), dataIndex: 'table', width: 200 },
        { title: t('integrity.row'), dataIndex: 'key', width: 200 },
        { title: t('integrity.detail'), dataIndex: 'detail' },
      ]}
    />
  )
}

/**
 * The integrity seals (docs/design/21-audit-retention.md section 2.4): the head of the chain - its hash is worth
 * keeping outside the system -, verifying the seals (integrity.verify) and the verifications with what they found.
 */
export default function IntegrityPage() {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const [running, setRunning] = useState(false)
  const [detail, setDetail] = useState<IntegrityCheckDetail | null>(null)

  const head = useQuery({
    queryKey: ['integrity', 'head'],
    queryFn: async () => unwrap(api.GET('/api/integrity/head')),
  })
  const checks = useQuery({
    queryKey: ['integrity', 'checks'],
    queryFn: async () => unwrap(api.GET('/api/integrity/checks', { params: { query: { limit: 50 } } })),
  })

  const fail = (e: unknown) => message.error(e instanceof ApiError ? e.display : String(e))

  const open = async (checkNo: number) => {
    try {
      setDetail(await unwrap(api.GET('/api/integrity/checks/{checkNo}', { params: { path: { checkNo } } })))
    } catch (e) {
      fail(e)
    }
  }

  const verify = async () => {
    setRunning(true)
    try {
      const output = await runProcess<VerifyOutput>('INTEGRITY_VERIFY', {})
      if (output.intact) message.success(t('integrity.intact'))
      else message.error(t('integrity.problems', { count: output.problemCount }))
      await queryClient.invalidateQueries({ queryKey: ['integrity'] })
      await open(output.checkNo)
    } catch (e) {
      fail(e)
    } finally {
      setRunning(false)
    }
  }

  const current = head.data
  return (
    <PageContainer
      title={t('integrity.title')}
      extra={can('integrity.verify') && (
        <Button type="primary" loading={running} onClick={() => void verify()} data-testid="integrity-verify">
          {t('integrity.verify')}
        </Button>
      )}
    >
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <Card title={t('integrity.head')} data-testid="integrity-head">
          {current?.sealNo == null ? (
            <Typography.Text type="secondary">{t('integrity.noSeals')}</Typography.Text>
          ) : (
            <Descriptions column={{ xs: 1, md: 2 }} size="small">
              <Descriptions.Item label={t('integrity.seal')}>{current.sealNo}</Descriptions.Item>
              <Descriptions.Item label={t('integrity.sealedTime')}>{formatDateTime(current.sealedTime)}</Descriptions.Item>
              <Descriptions.Item label={t('integrity.rows')}>{current.rowCount}</Descriptions.Item>
              <Descriptions.Item label={t('integrity.keyId')}>{current.keyId}</Descriptions.Item>
              <Descriptions.Item label={t('integrity.hash')} span={2}>
                <Typography.Text copyable code data-testid="integrity-head-hash">{current.sealHash}</Typography.Text>
              </Descriptions.Item>
            </Descriptions>
          )}
          {current?.keyId && current.keyId !== current.currentKeyId && (
            <Alert type="warning" showIcon message={t('integrity.otherKey')} />
          )}
        </Card>
        <Card title={t('integrity.checks')}>
          <Table<IntegrityCheckSummary>
            size="small"
            rowKey="checkNo"
            loading={checks.isLoading}
            dataSource={checks.data?.items ?? []}
            pagination={{ pageSize: 20, hideOnSinglePage: true }}
            data-testid="integrity-checks"
            columns={[
              {
                title: t('integrity.checked'),
                dataIndex: 'checkedTime',
                render: (_, check) => (
                  <Space direction="vertical" size={0}>
                    <span>{formatDateTime(check.checkedTime)}</span>
                    <Typography.Text type="secondary"><UserName id={check.actorId} /></Typography.Text>
                  </Space>
                ),
              },
              {
                title: t('integrity.outcome'),
                dataIndex: 'intact',
                render: (_, check) => (
                  <Tag color={check.intact ? 'green' : 'red'} data-testid={`integrity-outcome-${check.checkNo}`}>
                    {check.intact ? t('integrity.intact') : t('integrity.problems', { count: check.problemCount })}
                  </Tag>
                ),
              },
              {
                title: t('integrity.seals'),
                key: 'seals',
                render: (_, check) => (check.sealCount ? `${check.fromSeal} – ${check.toSeal}` : '–'),
              },
              { title: t('integrity.rows'), dataIndex: 'rowCount', align: 'right' },
              { title: t('integrity.unsealed'), dataIndex: 'unsealedCount', align: 'right' },
              {
                title: '',
                key: 'open',
                render: (_, check) => (
                  <Button size="small" onClick={() => void open(check.checkNo!)} data-testid={`integrity-open-${check.checkNo}`}>
                    {t('integrity.details')}
                  </Button>
                ),
              },
            ]}
          />
        </Card>
      </Space>
      <Drawer width={1000} open={detail !== null} onClose={() => setDetail(null)}
        title={detail ? t('integrity.checkTitle', { no: detail.check?.checkNo }) : ''}>
        {detail && (detail.problems?.length
          ? <ProblemTable problems={detail.problems} />
          : <Alert type="success" showIcon message={t('integrity.intact')} />)}
        {detail && (detail.check?.problemCount ?? 0) > (detail.problems?.length ?? 0) && (
          <Alert type="info" showIcon message={t('integrity.more', { count: detail.check?.problemCount })} />
        )}
      </Drawer>
    </PageContainer>
  )
}
