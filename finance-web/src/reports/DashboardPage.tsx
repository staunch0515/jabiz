import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, paths } from '@jabiz/admin'
import { Alert, Card, Col, Input, Row, Space, Statistic, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { CLOSE_PATH } from '../close/paths'
import { loadDashboard, QUERIES } from './api'
import { statementPath } from './paths'
import { figure } from './format'

const message = (e: unknown) => (e instanceof ApiError ? e.display : String(e))
const DAY = /^\d{4}-\d{2}-\d{2}$/
/** Today where the user is, not in UTC. */
const today = () => {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

/**
 * The controller's dashboard (FIN-RP-021): on a day, the cash per the books, the open receivables and payables, the
 * month's revenue and net income and the month's close status, each opening the report it comes from. A figure the
 * user may not read shows as a dash.
 */
export default function DashboardPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [draft, setDraft] = useState(today)
  const [day, setDay] = useState(draft)
  const figures = useQuery({ queryKey: ['fin', 'dashboard', day], queryFn: () => loadDashboard(day),
    enabled: DAY.test(day) })
  const data = figures.data

  const tile = (key: string, value: unknown, to: string) => (
    <Col xs={24} sm={12} lg={8} key={key}>
      <Link to={to} data-testid={`tile-${key}`}>
        <Card size="small" hoverable>
          <Statistic title={t(`dashboard.${key}`)} loading={figures.isLoading}
            valueRender={() => <span data-testid={`value-${key}`}>{value === null || value === undefined || value === ''
              ? '—' : typeof value === 'string' && !/^-?\d/.test(value) ? value : figure(value)}</span>} />
        </Card>
      </Link>
    </Col>
  )

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{t('dashboard.title')}</Typography.Title>
      <Space>
        <Input aria-label={t('dashboard.day')} style={{ width: 150 }} value={draft} data-testid="dashboard-day"
          onChange={(e) => setDraft(e.target.value.trim())} onPressEnter={() => setDay(draft)}
          onBlur={() => setDay(draft)} />
      </Space>
      {figures.error && <Alert type="error" showIcon message={message(figures.error)} />}
      <Row gutter={[16, 16]}>
        {tile('cash', data?.cash, statementPath('balance-sheet', { asOf: day }))}
        {tile('receivables', data?.receivables, `${paths.report(QUERIES.arAging)}`)}
        {tile('payables', data?.payables, `${paths.report(QUERIES.apAging)}`)}
        {tile('revenue', data?.revenue, statementPath('income-statement', { through: day }))}
        {tile('netIncome', data?.netIncome, statementPath('income-statement', { through: day }))}
        {tile('close', data?.periodKey ? `${data.periodKey} ${t(`dashboard.status.${data.periodStatus}`,
          { defaultValue: data.periodStatus ?? '' })}` : '', CLOSE_PATH)}
      </Row>
    </Space>
  )
}
