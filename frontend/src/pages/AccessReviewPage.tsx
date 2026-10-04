import { PageContainer } from '@ant-design/pro-components'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Card, DatePicker, Form, Input, Space, Table, Tag, Typography } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { api, unwrap } from '../api/client'
import type { components } from '../api/schema'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
import { runProcess } from '../lib/calls'
import { formatDateTime } from '../meta/format'
import { paths } from './paths'
import UserName from '../components/UserName'

type Review = components['schemas']['Review']
type Conflict = components['schemas']['Conflict']
type Change = components['schemas']['AuditRecordEntry']

/** The access report (docs/design/10-security.md section 13.3). */
export const ACCESS_TEMPLATE = 'jabiz.security.access_review'
export const SIGN_OFF = 'ACCESS_REVIEW_SIGN_OFF'
const MAX_COMMENT = 2000

/** The last full month: the usual period of a review. */
function lastMonth(): [Dayjs, Dayjs] {
  const start = dayjs().subtract(1, 'month').startOf('month')
  return [start, start.endOf('month')]
}

/** A range of days as the half-open period [first day 00:00, day after the last 00:00). */
function periodOf(range: [Dayjs, Dayjs]): { from: string; to: string } {
  return { from: range[0].startOf('day').toISOString(), to: range[1].add(1, 'day').startOf('day').toISOString() }
}

/**
 * The periodic access review (docs/design/10-security.md section 13.3, decision D28 item 9): issue the access report
 * as of the end of the period (REPORT_ISSUE, kept in the report archive), look at the period's security changes from
 * the audit trail and the segregation-of-duties conflicts, and sign with a comment (ACCESS_REVIEW_SIGN_OFF, which
 * needs a recent second factor). The server checks every permission again.
 */
export default function AccessReviewPage() {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const [range, setRange] = useState<[Dayjs, Dayjs]>(lastMonth)
  const [runId, setRunId] = useState<string | null>(null)
  const [issuing, setIssuing] = useState(false)
  const [signing, setSigning] = useState(false)
  const [form] = Form.useForm<{ comment: string }>()
  const period = periodOf(range)
  const ended = !dayjs(period.to).isAfter(dayjs())

  const changes = useQuery({
    queryKey: ['access-review', 'changes', period.from, period.to],
    queryFn: async () =>
      unwrap(api.GET('/api/security/access-reviews/changes', { params: { query: { from: period.from, to: period.to } } })),
  })
  const conflicts = useQuery({
    queryKey: ['access-review', 'conflicts'],
    queryFn: async () => unwrap(api.GET('/api/security/access-reviews/conflicts')),
  })
  const reviews = useQuery({
    queryKey: ['access-review', 'reviews'],
    queryFn: async () => unwrap(api.GET('/api/security/access-reviews')),
  })

  const issue = async () => {
    setIssuing(true)
    try {
      const output = await runProcess<{ runId: string }>('REPORT_ISSUE', {
        templateId: ACCESS_TEMPLATE,
        params: { asOf: period.to },
      })
      setRunId(output.runId)
      message.success(t('accessReview.issued'))
    } catch (e) {
      message.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setIssuing(false)
    }
  }

  const sign = async ({ comment }: { comment: string }) => {
    if (!runId) return
    setSigning(true)
    try {
      await runProcess(SIGN_OFF, {
        periodFrom: period.from,
        periodTo: period.to,
        reportRunId: runId,
        reviewComment: comment,
      })
      message.success(t('accessReview.signed'))
      form.resetFields()
      setRunId(null)
      await queryClient.invalidateQueries({ queryKey: ['access-review', 'reviews'] })
    } catch (e) {
      message.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setSigning(false)
    }
  }

  return (
    <PageContainer title={t('accessReview.title')}>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Card title={t('accessReview.period')}>
          <Space wrap>
            <DatePicker.RangePicker
              value={range}
              allowClear={false}
              onChange={(value) => {
                if (value?.[0] && value[1]) {
                  setRange([value[0], value[1]])
                  setRunId(null)
                }
              }}
              data-testid="access-review-period"
            />
            {can('report.issue') && (
              <Button onClick={() => void issue()} loading={issuing} disabled={!ended} data-testid="access-review-issue">
                {t('accessReview.issue')}
              </Button>
            )}
            {runId && (
              <Link to={paths.reportArchive(ACCESS_TEMPLATE)} data-testid="access-review-report">
                {t('accessReview.openReport')}
              </Link>
            )}
          </Space>
          {!ended && <Alert style={{ marginTop: 16 }} type="info" showIcon message={t('accessReview.notEnded')} />}
        </Card>

        <Card title={t('accessReview.changes', { count: changes.data?.items?.length ?? 0 })}>
          <Table<Change>
            size="small"
            rowKey="recordNo"
            loading={changes.isLoading}
            dataSource={changes.data?.items ?? []}
            pagination={{ defaultPageSize: 20 }}
            data-testid="access-review-changes"
            columns={[
              {
                title: t('audit.recorded'),
                dataIndex: 'recordedTime',
                render: (value?: string) => (value ? formatDateTime(value) : ''),
              },
              { title: t('audit.actor'), dataIndex: 'actorId', render: (value?: string) => <UserName id={value} /> },
              { title: t('audit.entityType'), dataIndex: 'entityType' },
              { title: t('audit.entityId'), dataIndex: 'entityId' },
              { title: t('audit.action'), dataIndex: 'action', render: (value: string) => <Tag>{value}</Tag> },
              {
                title: t('audit.changed'),
                key: 'changed',
                render: (_: unknown, record: Change) => Object.keys(record.changes ?? {}).join(', '),
              },
            ]}
          />
        </Card>

        <Card title={t('accessReview.conflicts', { count: conflicts.data?.items?.length ?? 0 })}>
          <Table<Conflict>
            size="small"
            rowKey={(row) => `${row.userId}/${row.ruleCode}`}
            loading={conflicts.isLoading}
            dataSource={conflicts.data?.items ?? []}
            pagination={false}
            data-testid="access-review-conflicts"
            columns={[
              { title: t('accessReview.user'), dataIndex: 'userName' },
              { title: t('accessReview.rule'), dataIndex: 'ruleCode' },
              { title: t('accessReview.roles'), dataIndex: 'roles', render: (roles?: string[]) => (roles ?? []).join(', ') },
            ]}
          />
        </Card>

        {can('security.access-review.sign') && (
          <Card title={t('accessReview.sign')}>
            {!runId && <Typography.Paragraph type="secondary">{t('accessReview.issueFirst')}</Typography.Paragraph>}
            <Form form={form} layout="vertical" onFinish={(values) => void sign(values)}>
              <Form.Item
                name="comment"
                label={t('accessReview.comment')}
                rules={[{ required: true, whitespace: true, message: t('accessReview.commentRequired') }]}
              >
                <Input.TextArea rows={4} maxLength={MAX_COMMENT} showCount data-testid="access-review-comment" />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={signing} disabled={!runId || !ended}
                data-testid="access-review-sign">
                {t('accessReview.signAction')}
              </Button>
            </Form>
          </Card>
        )}

        <Card title={t('accessReview.signedReviews')}>
          <Table<Review>
            size="small"
            rowKey="reviewId"
            loading={reviews.isLoading}
            dataSource={reviews.data ?? []}
            pagination={{ defaultPageSize: 10 }}
            data-testid="access-review-list"
            columns={[
              {
                title: t('accessReview.period'),
                key: 'period',
                render: (_: unknown, review: Review) =>
                  `${review.periodFrom ? formatDateTime(review.periodFrom) : ''} – ${
                    review.periodTo ? formatDateTime(review.periodTo) : ''}`,
              },
              { title: t('accessReview.reviewer'), dataIndex: 'reviewer' },
              {
                title: t('accessReview.signedAt'),
                dataIndex: 'signedAt',
                render: (value?: string) => (value ? formatDateTime(value) : ''),
              },
              { title: t('accessReview.changeCount'), dataIndex: 'changesCount', align: 'right' },
              {
                title: t('accessReview.conflictCount'),
                key: 'conflicts',
                align: 'right',
                render: (_: unknown, review: Review) => review.conflicts?.length ?? 0,
              },
              { title: t('accessReview.comment'), dataIndex: 'comment', ellipsis: true },
            ]}
          />
        </Card>
      </Space>
    </PageContainer>
  )
}
