import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, formatAmount, formatDate, useAuth, UserName } from '@jabiz/admin'
import { Alert, Button, Card, Select, Space, Table, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import { loadBanks, loadReconciliations, loadStatements, PERMISSIONS, prepare, type Reconciliation } from './api'
import { reconciliationPath } from './paths'
import { RecStatusTag } from './StatusTag'

const money = (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 })
const message = (e: unknown) => (e instanceof ApiError ? e.display : String(e))

/**
 * The bank reconciliations (FIN-BK-007, 008): each account's reconciliations by statement day with their figures,
 * state, preparer and reviewer, each opening its page; and a new one prepared for a statement's closing day.
 */
export default function ReconciliationListPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const [bankCode, setBankCode] = useState<string | null>(null)
  const banks = useQuery({ queryKey: ['fin', 'bank', 'accounts'], queryFn: loadBanks })
  const recs = useQuery({ queryKey: ['fin', 'bank', 'recs', bankCode], queryFn: () => loadReconciliations(bankCode) })

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{t('bank.reconciliationsTitle')}</Typography.Title>
      {can(PERMISSIONS.reconcile) && <PrepareCard banks={banks.data?.map((b) => b.bankCode) ?? []} />}
      <Select<string | null> aria-label={t('bank.account')} style={{ width: 260 }} value={bankCode} onChange={setBankCode}
        options={[{ value: null, label: t('bank.allAccounts') },
          ...(banks.data ?? []).map((b) => ({ value: b.bankCode, label: b.bankCode }))]} />
      <Table<Reconciliation>
        data-testid="rec-table"
        size="small"
        rowKey="reconciliationId"
        loading={recs.isLoading}
        dataSource={recs.data ?? []}
        pagination={{ pageSize: 50, showSizeChanger: false }}
        locale={{ emptyText: recs.error ? message(recs.error) : t('bank.noReconciliations') }}
        columns={[
          { title: t('bank.account'), dataIndex: 'bankCode' },
          { title: t('bank.statementDate'), dataIndex: 'statementDate',
            render: (v: string, row) => <Link to={reconciliationPath(row.reconciliationId)}>{formatDate(v)}</Link> },
          { title: t('bank.statementBalance'), dataIndex: 'statementBalance', align: 'right', render: money },
          { title: t('bank.adjustedBalance'), dataIndex: 'adjustedBalance', align: 'right', render: money },
          { title: t('bank.bookBalance'), dataIndex: 'bookBalance', align: 'right', render: money },
          { title: t('bank.difference'), dataIndex: 'difference', align: 'right', render: money },
          { title: t('bank.status'), dataIndex: 'status', render: (v: Reconciliation['status']) => <RecStatusTag status={v} /> },
          { title: t('bank.preparedBy'), dataIndex: 'preparedBy', render: (value?: string | null) => <UserName id={value} /> },
          { title: t('bank.reviewedBy'), dataIndex: 'reviewedBy', render: (value?: string | null) => <UserName id={value} /> },
        ]}
      />
    </Space>
  )
}

/** A new reconciliation, or one prepared again, for a statement's closing day. */
function PrepareCard({ banks }: { banks: string[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const navigate = useNavigate()
  const [bankCode, setBankCode] = useState<string | null>(null)
  const [day, setDay] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const statements = useQuery({ queryKey: ['fin', 'bank', bankCode, 'statements'],
    queryFn: () => loadStatements(bankCode as string), enabled: Boolean(bankCode) })

  const go = async () => {
    if (!bankCode || !day) return
    setBusy(true)
    setError(null)
    try {
      const rec = await prepare(bankCode, day)
      navigate(reconciliationPath(rec.reconciliationId))
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card size="small" title={t('bank.prepareTitle')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} data-testid="prepare-error" />}
        <Space wrap>
          <Select<string> aria-label={t('bank.account')} placeholder={t('bank.chooseAccount')} style={{ width: 220 }}
            value={bankCode ?? undefined} data-testid="prepare-bank" showSearch
            onChange={(v) => { setBankCode(v); setDay(null) }} options={banks.map((b) => ({ value: b, label: b }))} />
          <Select<string> aria-label={t('bank.statementDate')} placeholder={t('bank.chooseStatement')} style={{ width: 300 }}
            value={day ?? undefined} loading={statements.isLoading} disabled={!bankCode} data-testid="prepare-day"
            onChange={setDay} options={(statements.data ?? []).map((s) => ({ value: s.toDate,
              label: t('bank.statementOption', { day: formatDate(s.toDate), balance: money(s.closingBalance) }) }))} />
          <Button type="primary" disabled={!bankCode || !day || busy} loading={busy} onClick={() => void go()}
            data-testid="prepare">{t('bank.prepare')}</Button>
        </Space>
      </Space>
    </Card>
  )
}
