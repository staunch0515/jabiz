import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, formatAmount, formatDate, paths, runProcess, useAuth } from '@jabiz/admin'
import { Alert, App, Button, Card, Descriptions, Input, Select, Space, Table, Typography } from 'antd'
import { useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router'
import { loadAccounts } from '../journal/api'
import {
  DATASETS,
  loadCustomers,
  loadInvoiceNumbers,
  loadReceipt,
  loadSuggestions,
  PERMISSIONS,
  PROCESSES,
  RECEIPT_METHODS,
  recordReceipt,
  type Application,
  type LoadedReceipt,
} from './api'
import ApplyTable from './ApplyTable'
import Field from './Field'
import { sign, stored } from './money'
import { invoicePath, receiptPath, RECEIPTS_PATH } from './paths'
import { only, readAmount, toApplications, unapplied, unreadable, type Entries } from './receipt'
import { ReceiptStatusTag } from './StatusTags'

interface Header {
  customerCode: string
  receiptDate: string
  amount: string
  method: string
  reference: string
  bankAccount: string
  description: string
}

/** The bank accounts a receipt may be deposited to: active control accounts of class BANK. */
function useBankAccounts() {
  const accounts = useQuery({ queryKey: ['fin', 'accounts'], queryFn: loadAccounts, staleTime: 60_000 })
  return useMemo(() => [...(accounts.data?.values() ?? [])]
    .filter((a) => a.controlClass === 'BANK' && a.active && !a.summary), [accounts.data])
}

/**
 * A customer receipt (FIN-AR-007, 008; FIN-UI-002): a new one is recorded here with its applications to the
 * customer's open invoices, suggested from the reference and the amount (FIN_RECEIPT_RECORD); an existing one shows
 * what it paid, applies what is left unapplied (FIN_RECEIPT_APPLY) and takes an application back
 * (FIN_APPLICATION_REVERSE, not the recording clerk's). Ctrl+Enter records or applies.
 */
export default function ReceiptPage() {
  const { receiptId } = useParams<{ receiptId: string }>()
  const loaded = useQuery({
    queryKey: ['fin', 'receipt', receiptId],
    queryFn: () => loadReceipt(receiptId as string),
    enabled: Boolean(receiptId),
  })
  if (!receiptId) return <NewReceipt />
  if (loaded.isLoading) return <Card loading />
  if (loaded.error || !loaded.data) {
    return <Alert type="error" showIcon message={loaded.error instanceof ApiError ? loaded.error.display : String(loaded.error)} />
  }
  return <ExistingReceipt loaded={loaded.data} />
}

function NewReceipt() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { message } = App.useApp()
  const navigate = useNavigate()
  const formId = useId()
  const banks = useBankAccounts()
  const customers = useQuery({ queryKey: ['fin', 'customers'], queryFn: loadCustomers, staleTime: 60_000 })
  const [header, setHeader] = useState<Header>({ customerCode: '', receiptDate: '', amount: '', method: 'CHECK',
    reference: '', bankAccount: '', description: '' })
  const [entries, setEntries] = useState<Entries>({})
  const [busy, setBusy] = useState(false)
  const [problems, setProblems] = useState<string[]>([])
  const idempotency = useRef<{ key: string; body: string } | null>(null)
  const bankAccount = header.bankAccount || banks[0]?.accountCode || ''
  const customer = customers.data?.find((c) => c.customerCode === header.customerCode.trim())
  const ready = Boolean(customer && header.receiptDate)
  const suggestions = useQuery({
    queryKey: ['fin', 'suggestions', header.customerCode, header.receiptDate, header.amount, header.reference],
    queryFn: () => loadSuggestions({ customerCode: header.customerCode.trim(), onDate: header.receiptDate,
      amount: readAmount(header.amount) ? header.amount.replace(/[$,\s]/g, '') : null, reference: header.reference }),
    enabled: ready,
  })
  const change = (patch: Partial<Header>) => {
    setHeader((h) => ({ ...h, ...patch }))
    setProblems([])
    if (patch.customerCode !== undefined) setEntries({})
  }

  const amountOk = Boolean(readAmount(header.amount))
  const ids = (suggestions.data ?? []).map((s) => s.invoiceId)
  const shown = only(entries, ids)
  const over = amountOk && sign(stored(unapplied(header.amount, shown))) < 0
  // Not while the open invoices reload: what is applied is what is shown.
  const canRecord = ready && amountOk && Boolean(bankAccount) && !over && !unreadable(shown) && !suggestions.isFetching

  const onRecord = async () => {
    if (busy || !canRecord) return
    setBusy(true)
    const input = {
      customerCode: header.customerCode.trim(),
      receiptDate: header.receiptDate,
      amount: header.amount.replace(/[$,\s]/g, ''),
      method: header.method,
      reference: header.reference.trim() || null,
      bankAccount,
      description: header.description.trim() || null,
      applications: toApplications(shown, ids),
    }
    const body = JSON.stringify(input)
    if (idempotency.current?.body !== body) idempotency.current = { key: crypto.randomUUID(), body }
    try {
      const output = await recordReceipt(input, idempotency.current.key)
      message.success(t('receivables.receipt.recorded', { number: output.receiptNo }))
      navigate(receiptPath(output.receiptId))
    } catch (error) {
      setProblems(error instanceof ApiError ? (error.violations.length > 0 ? error.violations.map((v) => v.message)
        : [error.display]) : [String(error)])
    } finally {
      setBusy(false)
    }
  }

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      event.preventDefault()
      void onRecord()
    }
  }

  return (
    <div onKeyDown={onKeyDown} data-testid="receipt-page">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space align="center">
          <Link to={RECEIPTS_PATH}>{t('receivables.backToReceipts')}</Link>
          <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">
            {t('receivables.receipt.titleNew')}
          </Typography.Title>
        </Space>
        <Card size="small">
          <datalist id={`${formId}-customers`}>
            {(customers.data ?? []).map((c) => <option key={c.customerCode} value={c.customerCode}>{c.legalName}</option>)}
          </datalist>
          <Space wrap size="large" align="end">
            <Field id={`${formId}-customer`} label={t('receivables.customer')}>
              <Input id={`${formId}-customer`} list={`${formId}-customers`} value={header.customerCode} autoFocus
                style={{ width: 160 }} data-testid="receipt-customer"
                onChange={(e) => change({ customerCode: e.target.value.toUpperCase() })} />
            </Field>
            <Typography.Text data-testid="receipt-customer-name" style={{ minWidth: 160 }}>{customer?.legalName ?? ''}</Typography.Text>
            <Field id={`${formId}-date`} label={t('receivables.receipt.receiptDate')}>
              <Input id={`${formId}-date`} type="date" value={header.receiptDate} style={{ width: 170 }}
                data-testid="receipt-date" onChange={(e) => change({ receiptDate: e.target.value })} />
            </Field>
            <Field id={`${formId}-amount`} label={t('receivables.receipt.amount')}>
              <Input id={`${formId}-amount`} value={header.amount} inputMode="decimal" style={{ width: 150, textAlign: 'right' }}
                status={header.amount && !amountOk ? 'error' : undefined} data-testid="receipt-amount"
                onChange={(e) => change({ amount: e.target.value })} />
            </Field>
            <Field id={`${formId}-method`} label={t('receivables.receipt.method')}>
              <Select id={`${formId}-method`} value={header.method} style={{ width: 120 }}
                onChange={(method: string) => change({ method })}
                options={RECEIPT_METHODS.map((m) => ({ value: m, label: t(`receivables.receipt.methods.${m}`) }))} />
            </Field>
            <Field id={`${formId}-reference`} label={t('receivables.receipt.reference')}>
              <Input id={`${formId}-reference`} value={header.reference} maxLength={100} style={{ width: 200 }}
                onChange={(e) => change({ reference: e.target.value })} />
            </Field>
            <Field id={`${formId}-bank`} label={t('receivables.receipt.bankAccount')}>
              <Select id={`${formId}-bank`} value={bankAccount || undefined} style={{ width: 280 }}
                onChange={(value: string) => change({ bankAccount: value })}
                options={banks.map((a) => ({ value: a.accountCode, label: `${a.accountCode} ${a.accountName}` }))} />
            </Field>
            <Field id={`${formId}-description`} label={t('receivables.receipt.description')} grow>
              <Input id={`${formId}-description`} value={header.description} maxLength={500}
                onChange={(e) => change({ description: e.target.value })} />
            </Field>
          </Space>
        </Card>
        {problems.length > 0 && (
          <Alert type="error" showIcon data-testid="receipt-problems" message={t('receivables.refused')}
            description={<ul style={{ margin: 0, paddingLeft: 18 }}>{problems.map((p) => <li key={p}>{p}</li>)}</ul>} />
        )}
        <Card size="small" title={t('receivables.receipt.openInvoices')}>
          <ApplyTable amount={header.amount} suggestions={suggestions.data ?? []} loading={suggestions.isFetching && ready}
            entries={shown} onChange={setEntries} ready={ready} />
        </Card>
        <Space>
          <Button type="primary" disabled={!canRecord || busy} loading={busy} onClick={() => void onRecord()}
            title="Ctrl+Enter" data-testid="receipt-record">
            {t('receivables.receipt.record')}
          </Button>
        </Space>
      </Space>
    </div>
  )
}

function ExistingReceipt({ loaded }: { loaded: LoadedReceipt }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const { receipt, applications } = loaded
  const numbers = useQuery({
    queryKey: ['fin', 'invoiceNumbers', applications.map((a) => a.invoiceId).join(',')],
    queryFn: () => loadInvoiceNumbers(applications.map((a) => a.invoiceId)),
  })
  const money = (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 })
  const open = receipt.status === 'POSTED' && sign(stored(receipt.unappliedAmount)) > 0

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }} data-testid="receipt-view">
      <Space align="center">
        <Link to={RECEIPTS_PATH}>{t('receivables.backToReceipts')}</Link>
        <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">
          {t('receivables.receipt.title', { number: receipt.receiptNo ?? '' })}
        </Typography.Title>
        <ReceiptStatusTag status={receipt.status} />
      </Space>
      <Descriptions size="small" bordered column={{ xs: 1, md: 3 }} data-testid="receipt-facts">
        <Descriptions.Item label={t('receivables.customer')}>{receipt.customerCode}</Descriptions.Item>
        <Descriptions.Item label={t('receivables.receipt.receiptDate')}>{formatDate(receipt.receiptDate)}</Descriptions.Item>
        <Descriptions.Item label={`${t('receivables.receipt.amount')} (${receipt.currency})`}>{money(receipt.amount)}</Descriptions.Item>
        <Descriptions.Item label={t('receivables.receipt.method')}>
          {t(`receivables.receipt.methods.${receipt.method}`, receipt.method)}
        </Descriptions.Item>
        <Descriptions.Item label={t('receivables.receipt.reference')}>{receipt.reference ?? '—'}</Descriptions.Item>
        <Descriptions.Item label={t('receivables.receipt.bankAccount')}>{receipt.bankAccount}</Descriptions.Item>
        <Descriptions.Item label={t('receivables.receipt.unapplied')}>
          <span data-testid="receipt-unapplied">{money(receipt.unappliedAmount)}</span>
        </Descriptions.Item>
        <Descriptions.Item label={t('receivables.facts.preparedBy')}>{receipt.preparedBy ?? '—'}</Descriptions.Item>
        <Descriptions.Item label={t('receivables.facts.history')}>
          <Link to={paths.history(DATASETS.receipt, receipt.receiptId)}>{t('receivables.facts.viewHistory')}</Link>
        </Descriptions.Item>
      </Descriptions>

      <Card size="small" title={t('receivables.receipt.applications')}>
        <ApplicationsTable receiptId={receipt.receiptId} applications={applications} numbers={numbers.data} />
      </Card>

      {open && can(PERMISSIONS.receipt) && <ApplyMore loaded={loaded} />}
    </Space>
  )
}

/** The receipt's applications, newest last; one not yet taken back can be (FIN-AR-008), by those allowed to. */
function ApplicationsTable({ receiptId, applications, numbers }: { receiptId: string; applications: Application[];
  numbers?: Map<string, string> }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const [reversing, setReversing] = useState<string | null>(null)
  const [date, setDate] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const reversed = new Set(applications.map((a) => a.reversesApplicationId).filter(Boolean))
  const money = (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 })

  const onReverse = async (applicationId: string) => {
    setBusy(true)
    setError(null)
    try {
      await runProcess(PROCESSES.reverse, { applicationId, reverseDate: date, reason: reason.trim() })
      message.success(t('receivables.receipt.reversed'))
      setReversing(null)
      setReason('')
      await queryClient.invalidateQueries({ queryKey: ['fin', 'receipt', receiptId] })
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {error && <Alert type="error" showIcon message={error} data-testid="reverse-error" />}
      <Table<Application>
        size="small"
        rowKey="applicationId"
        pagination={false}
        dataSource={applications}
        data-testid="receipt-applications"
        locale={{ emptyText: t('receivables.noApplications') }}
        columns={[
          { title: t('receivables.appliedDate'), dataIndex: 'applicationDate', render: (v: string) => formatDate(v) },
          { title: t('receivables.number'), dataIndex: 'invoiceId',
            render: (id: string) => <Link to={invoicePath(id)}>{numbers?.get(id) ?? '…'}</Link> },
          { title: t('receivables.appliedAmount'), dataIndex: 'amount', align: 'right', render: money },
          { title: t('receivables.appliedDiscount'), dataIndex: 'discount', align: 'right',
            render: (v: unknown) => (v === null || v === undefined ? '' : money(v)) },
          { title: t('receivables.appliedReason'), dataIndex: 'reason',
            render: (v: string | null, row) => (row.reversesApplicationId ? `${t('receivables.reversal')}: ${v ?? ''}` : v ?? '') },
          {
            key: 'reverse',
            render: (_, row) => (can(PERMISSIONS.adjust) && !row.reversesApplicationId && !reversed.has(row.applicationId)
              ? <a onClick={() => setReversing(row.applicationId)} data-testid={`reverse-${row.applicationId}`}>
                  {t('receivables.receipt.reverse')}</a>
              : null),
          },
        ]}
      />
      {reversing && (
        <Space wrap>
          <Typography.Text strong>{t('receivables.receipt.reverseTitle')}</Typography.Text>
          <Input type="date" value={date} onChange={(e) => setDate(e.target.value)}
            aria-label={t('receivables.receipt.reverseDate')} style={{ width: 170 }} />
          <Input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} style={{ width: 320 }}
            aria-label={t('receivables.receipt.reverseReason')} placeholder={t('receivables.receipt.reverseReason')} />
          <Button danger disabled={busy || !date || !reason.trim()} onClick={() => void onReverse(reversing)}
            data-testid="reverse-confirm">
            {t('receivables.receipt.reverse')}
          </Button>
        </Space>
      )}
    </Space>
  )
}

/** What is unapplied of a receipt applied to the customer's open invoices on a day (FIN_RECEIPT_APPLY). */
function ApplyMore({ loaded }: { loaded: LoadedReceipt }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const { receipt } = loaded
  const [date, setDate] = useState(receipt.receiptDate)
  const [entries, setEntries] = useState<Entries>({})
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const amount = String(receipt.unappliedAmount)
  const suggestions = useQuery({
    queryKey: ['fin', 'suggestions', receipt.customerCode, date, amount, receipt.reference],
    queryFn: () => loadSuggestions({ customerCode: receipt.customerCode, onDate: date, amount,
      reference: receipt.reference }),
    enabled: Boolean(date),
  })
  const ids = (suggestions.data ?? []).map((s) => s.invoiceId)
  const shown = only(entries, ids)
  const applications = toApplications(shown, ids)
  const ok = applications.length > 0 && !unreadable(shown) && sign(stored(unapplied(amount, shown))) >= 0
    && !suggestions.isFetching

  const onApply = async () => {
    setBusy(true)
    setError(null)
    try {
      await runProcess(PROCESSES.apply, { receiptId: receipt.receiptId, applicationDate: date, applications })
      message.success(t('receivables.receipt.appliedOk'))
      setEntries({})
      await queryClient.invalidateQueries({ queryKey: ['fin', 'receipt', receipt.receiptId] })
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card size="small" title={t('receivables.receipt.applyMore')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <label>
          {t('receivables.receipt.applicationDate')}{' '}
          <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} style={{ width: 170 }}
            aria-label={t('receivables.receipt.applicationDate')} />
        </label>
        {error && <Alert type="error" showIcon message={error} data-testid="apply-error" />}
        <ApplyTable amount={amount} suggestions={suggestions.data ?? []} loading={suggestions.isFetching}
          entries={shown} onChange={setEntries} ready={Boolean(date)} />
        <Button type="primary" disabled={!ok || busy} loading={busy} onClick={() => void onApply()}
          data-testid="receipt-apply">
          {t('receivables.receipt.apply')}
        </Button>
      </Space>
    </Card>
  )
}
