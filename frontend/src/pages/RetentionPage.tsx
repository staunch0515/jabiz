import { PageContainer } from '@ant-design/pro-components'
import { useQuery } from '@tanstack/react-query'
import { App, Button, Card, Checkbox, DatePicker, Form, Space, Table, Tag, Typography } from 'antd'
import AccessibleSelect from '../components/AccessibleSelect'
import type { Dayjs } from 'dayjs'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import {
  exportData, LEGAL_HOLD_DATASET, LEGAL_HOLD_PLACE, LEGAL_HOLD_RELEASE, type RetentionPolicyStatus,
} from '../api/retention'
import { useAuth } from '../auth/AuthContext'
import { useDatasets } from '../meta/hooks'
import { paths } from './paths'

interface ExportForm {
  datasets: string[]
  asOf?: Dayjs
  reports?: boolean
  reportsPeriod?: [Dayjs, Dayjs]
}

/**
 * Retention and archiving (docs/design/21-audit-retention.md sections 3 and 4): what is past its retention and what
 * legal holds keep (retention.read), the legal holds (their generated pages), and the open-format export of datasets
 * with the issued reports (data.export). The server checks every permission again.
 */
export default function RetentionPage() {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const { can } = useAuth()
  const datasets = useDatasets()
  const [exporting, setExporting] = useState(false)
  const [form] = Form.useForm<ExportForm>()

  const report = useQuery({
    queryKey: ['retention'],
    queryFn: async () => unwrap(api.GET('/api/retention')),
    enabled: can('retention.read'),
  })

  const submit = async (values: ExportForm) => {
    setExporting(true)
    try {
      await exportData({
        datasets: values.datasets,
        asOf: values.asOf?.toISOString(),
        reports: values.reports,
        reportsFrom: values.reportsPeriod?.[0]?.startOf('day').toISOString(),
        reportsTo: values.reportsPeriod?.[1]?.add(1, 'day').startOf('day').toISOString(),
      })
      message.success(t('retention.exported'))
    } catch (e) {
      message.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setExporting(false)
    }
  }

  return (
    <PageContainer title={t('retention.title')}>
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        {can('retention.read') && (
          <Card title={t('retention.policies')}>
            {report.data && (
              <Typography.Paragraph type="secondary">
                {t('retention.asOf', { today: report.data.today, month: report.data.fiscalYearEnd })}
              </Typography.Paragraph>
            )}
            <Table<RetentionPolicyStatus>
              size="small"
              rowKey="entity"
              loading={report.isLoading}
              dataSource={report.data?.policies ?? []}
              pagination={false}
              data-testid="retention-policies"
              columns={[
                { title: t('retention.entity'), dataIndex: 'entity' },
                {
                  title: t('retention.keep'),
                  key: 'keep',
                  render: (_, p) => (
                    <Space size={4}>
                      <span>{p.keep}</span>
                      <Typography.Text type="secondary">{t('retention.from', { field: p.from })}</Typography.Text>
                      {p.fromFiscalYearEnd && <Tag>{t('retention.fiscalYearEnd')}</Tag>}
                    </Space>
                  ),
                },
                { title: t('retention.expiredThrough'), dataIndex: 'expiredThrough' },
                { title: t('retention.entries'), dataIndex: 'entries', align: 'right' },
                {
                  title: t('retention.expired'),
                  dataIndex: 'expired',
                  align: 'right',
                  render: (_, p) => <span data-testid={`retention-expired-${p.entity}`}>{p.expired}</span>,
                },
                { title: t('retention.held'), dataIndex: 'held', align: 'right' },
              ]}
            />
          </Card>
        )}
        {(can('legal.hold.read') || can('legal.hold.write')) && (
          <Card title={t('retention.holds')}>
            <Space wrap>
              <Link to={paths.dataset(LEGAL_HOLD_DATASET)} data-testid="retention-holds">{t('retention.showHolds')}</Link>
              {can('legal.hold.write') && (
                <>
                  <Link to={paths.process(LEGAL_HOLD_PLACE, 1)}>{t('retention.placeHold')}</Link>
                  <Link to={paths.process(LEGAL_HOLD_RELEASE, 1)}>{t('retention.releaseHold')}</Link>
                </>
              )}
            </Space>
          </Card>
        )}
        {can('data.export') && (
          <Card title={t('retention.export')}>
            <Typography.Paragraph type="secondary">{t('retention.exportHint')}</Typography.Paragraph>
            <Form<ExportForm> form={form} layout="vertical" onFinish={(values) => void submit(values)}
              data-testid="export-form">
              <Form.Item name="datasets" label={t('retention.datasets')} rules={[{ required: true }]}>
                <AccessibleSelect
                  mode="multiple"
                  showSearch
                  optionFilterProp="label"
                  data-testid="export-datasets"
                  options={(datasets.data ?? []).map((d) => ({ value: d.id, label: `${d.label} (${d.id})` }))}
                />
              </Form.Item>
              <Form.Item name="asOf" label={t('retention.exportAsOf')}>
                <DatePicker showTime />
              </Form.Item>
              <Form.Item name="reports" valuePropName="checked">
                <Checkbox>{t('retention.withReports')}</Checkbox>
              </Form.Item>
              <Form.Item noStyle shouldUpdate>
                {() => form.getFieldValue('reports') && (
                  <Form.Item name="reportsPeriod" label={t('retention.reportsPeriod')}>
                    <DatePicker.RangePicker />
                  </Form.Item>
                )}
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={exporting} data-testid="export-submit">
                {t('retention.exportNow')}
              </Button>
            </Form>
          </Card>
        )}
      </Space>
    </PageContainer>
  )
}
