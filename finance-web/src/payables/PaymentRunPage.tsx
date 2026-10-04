import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  ApprovalPanel,
  EXTENSION_NAMESPACE,
  formatAmount,
  formatDate,
  paths,
  runProcess,
  useAuth,
  UserName,
} from '@jabiz/admin'
import { Alert, App, Button, Card, Descriptions, Input, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd'
import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useLocation, useParams } from 'react-router'
import { parseNumber } from '../receivables/money'
import {
  DATASETS,
  downloadGeneratedFile,
  FILE_KINDS,
  findBill,
  loadRun,
  PERMISSIONS,
  PROCESSES,
  type FileKind,
  type Held,
  type LineKind,
  type LoadedRun,
  type Payment,
  type PaymentFile,
  type RunLine,
} from './api'
import { billPath, RUNS_PATH } from './paths'
import { RunStatusTag } from './StatusTags'

const money = (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 })
const message = (e: unknown) => (e instanceof ApiError ? e.display : String(e))

/**
 * A payment run (FIN-AP-010…015, FIN-BK-011): what it pays and, just proposed, what it held and why; a draft's lines
 * are added (a bill by its number, another payment, a prepayment) and removed, then the run is submitted for
 * approval by another person, who decides here; the treasury releases it (with a second factor, asked for by the
 * platform), which posts its payments; then the files the bank takes are made, downloaded and, if refused, cancelled
 * with why, and a payment is voided when it must be. Each action is the server's process; the page offers what the
 * user may do.
 */
export default function PaymentRunPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { runId } = useParams<{ runId: string }>()
  const location = useLocation()
  const held = (location.state as { held?: Held[] } | null)?.held ?? []
  // The decision on a submitted run comes back through the platform's events a moment later: look again until then.
  const loaded = useQuery({ queryKey: ['fin', 'run', runId], queryFn: () => loadRun(runId as string),
    refetchInterval: (query) => (query.state.data?.run.status === 'SUBMITTED' ? 2_000 : false) })

  if (loaded.isLoading) return <Card loading />
  if (loaded.error || !loaded.data) return <Alert type="error" showIcon message={message(loaded.error)} />
  const { run } = loaded.data

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }} data-testid="run-page">
      <Space align="center" wrap>
        <Link to={RUNS_PATH}>{t('payables.backToRuns')}</Link>
        <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">
          {t('payables.titleRun', { number: run.runNo })}
        </Typography.Title>
        <RunStatusTag status={run.status} />
      </Space>
      {held.length > 0 && <HeldTable held={held} />}
      <Descriptions size="small" bordered column={{ xs: 1, md: 3 }} data-testid="run-facts">
        <Descriptions.Item label={t('payables.paymentDate')}>{formatDate(run.paymentDate)}</Descriptions.Item>
        <Descriptions.Item label={t('payables.method')}>{t(`payables.methods.${run.method}`, run.method)}</Descriptions.Item>
        <Descriptions.Item label={t('payables.bank')}>{run.bankCode}</Descriptions.Item>
        <Descriptions.Item label={t('payables.total')}><span data-testid="run-total">{money(run.total)}</span></Descriptions.Item>
        <Descriptions.Item label={t('payables.lineCount')}>{String(run.lineCount ?? 0)}</Descriptions.Item>
        <Descriptions.Item label={t('payables.description')}>{run.description ?? '—'}</Descriptions.Item>
        <Descriptions.Item label={t('payables.preparedBy')}>{run.preparedBy ? <UserName id={run.preparedBy} /> : '—'}</Descriptions.Item>
        <Descriptions.Item label={t('payables.approvedBy')}>{run.approvedBy ? <UserName id={run.approvedBy} /> : '—'}</Descriptions.Item>
        <Descriptions.Item label={t('payables.releasedBy')}>{run.releasedBy ? <UserName id={run.releasedBy} /> : '—'}</Descriptions.Item>
        {run.cancelReason && (
          <Descriptions.Item label={t('payables.cancelReason')} span={3}>{run.cancelReason}</Descriptions.Item>
        )}
        <Descriptions.Item label={t('payables.facts.history')}>
          <Link to={paths.history(DATASETS.run, run.runId)}>{t('payables.facts.viewHistory')}</Link>
        </Descriptions.Item>
      </Descriptions>
      <LinesCard loaded={loaded.data} />
      <Actions loaded={loaded.data} />
      {run.status === 'RELEASED' && <PaymentsCard loaded={loaded.data} />}
      {run.status === 'RELEASED' && FILE_KINDS[run.method].length > 0 && <FilesCard loaded={loaded.data} />}
    </Space>
  )
}

function HeldTable({ held }: { held: Held[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <Card size="small" title={t('payables.heldTitle')}>
      <Table<Held>
        size="small"
        rowKey="billId"
        pagination={false}
        dataSource={held}
        data-testid="held-table"
        columns={[
          { title: t('payables.billNo'), dataIndex: 'billNo', render: (v: string, row) => <Link to={billPath(row.billId)}>{v}</Link> },
          { title: t('payables.vendor'), dataIndex: 'vendorCode' },
          { title: t('payables.amount'), dataIndex: 'amount', align: 'right', render: money },
          { title: t('payables.heldReason'), dataIndex: 'reason' },
        ]}
      />
    </Card>
  )
}

/** The run's lines; in a draft each can be removed and new ones added. */
function LinesCard({ loaded }: { loaded: LoadedRun }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const { run } = loaded
  const editable = run.status === 'DRAFT' && can(PERMISSIONS.payment)
  const [error, setError] = useState<string | null>(null)
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['fin', 'run', run.runId] })

  const remove = async (line: RunLine) => {
    setError(null)
    try {
      await runProcess(PROCESSES.remove, { runId: run.runId, lineId: line.lineId })
      await refresh()
    } catch (e) {
      setError(message(e))
    }
  }

  return (
    <Card size="small" title={t('payables.lines')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} />}
        <Table<RunLine>
          size="small"
          rowKey="lineId"
          pagination={false}
          dataSource={loaded.lines}
          data-testid="run-lines"
          locale={{ emptyText: t('payables.noLines') }}
          columns={[
            { title: t('payables.kind'), dataIndex: 'kind', render: (v: LineKind) => t(`payables.lineKinds.${v}`) },
            { title: t('payables.billNo'), dataIndex: 'billNo',
              render: (v: string | null, row) => (row.billId ? <Link to={billPath(row.billId)}>{v}</Link> : '') },
            { title: t('payables.vendor'), dataIndex: 'vendorCode' },
            { title: t('payables.payee'), dataIndex: 'payee' },
            { title: t('payables.account'), dataIndex: 'account' },
            { title: t('payables.description'), dataIndex: 'description', ellipsis: true },
            { title: t('payables.amount'), dataIndex: 'amount', align: 'right', render: money },
            { title: t('payables.discount'), dataIndex: 'discount', align: 'right',
              render: (v: unknown) => (v === null || v === undefined ? '' : money(v)) },
            ...(editable ? [{
              title: '',
              key: 'remove',
              render: (_: unknown, row: RunLine) => (
                <Button size="small" onClick={() => void remove(row)} data-testid={`remove-${row.billNo ?? row.payee}`}>
                  {t('payables.remove')}
                </Button>
              ),
            }] : []),
          ]}
        />
        {editable && <AddLine runId={run.runId} onAdded={refresh} onError={setError} />}
      </Space>
    </Card>
  )
}

/** A line added to a draft: a bill by its number, another payment to an account, or a vendor's prepayment. */
function AddLine({ runId, onAdded, onError }: { runId: string; onAdded: () => Promise<void>;
  onError: (text: string | null) => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [kind, setKind] = useState<LineKind>('BILL')
  const [billNo, setBillNo] = useState('')
  const [vendorCode, setVendorCode] = useState('')
  const [payee, setPayee] = useState('')
  const [account, setAccount] = useState('')
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)
  // A retried add after a lost answer must not add an other payment or prepayment twice (a bill can be in one run only).
  const idempotency = useRef<{ key: string, body: string } | null>(null)

  const add = async () => {
    // Enter on a field and the button both add: a second press while the first is out would add the line twice.
    if (busy) return
    setBusy(true)
    onError(null)
    try {
      const parsed = parseNumber(amount, 2)
      const amountText = amount.trim() ? (parsed ? amount.replace(/[$,\s]/g, '') : amount.trim()) : null
      let input: Record<string, unknown>
      if (kind === 'BILL') {
        const bill = await findBill(billNo.trim())
        if (!bill) {
          onError(t('payables.billNotFound', { number: billNo.trim() }))
          return
        }
        input = { runId, kind, billId: bill.billId, amount: amountText }
      } else if (kind === 'OTHER') {
        input = { runId, kind, payee: payee.trim(), account: account.trim(), amount: amountText,
          description: description.trim() || null }
      } else {
        input = { runId, kind, vendorCode: vendorCode.trim(), amount: amountText, description: description.trim() || null }
      }
      const body = JSON.stringify(input)
      if (idempotency.current?.body !== body) idempotency.current = { key: crypto.randomUUID(), body }
      await runProcess(PROCESSES.add, input, { idempotencyKey: idempotency.current.key })
      idempotency.current = null
      setBillNo('')
      setPayee('')
      setAccount('')
      setAmount('')
      setDescription('')
      setVendorCode('')
      await onAdded()
    } catch (e) {
      onError(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Space wrap align="end" data-testid="add-line">
      <Select<LineKind> aria-label={t('payables.kind')} value={kind} onChange={setKind} style={{ width: 170 }}
        options={(['BILL', 'OTHER', 'PREPAYMENT'] as LineKind[]).map((k) => ({ value: k, label: t(`payables.lineKinds.${k}`) }))} />
      {kind === 'BILL' && (
        <Input aria-label={t('payables.billNo')} placeholder={t('payables.billNo')} value={billNo} style={{ width: 160 }}
          data-testid="add-bill-no" onChange={(e) => setBillNo(e.target.value.toUpperCase())}
          onPressEnter={() => void add()} />
      )}
      {kind === 'OTHER' && (
        <>
          <Input aria-label={t('payables.payee')} placeholder={t('payables.payee')} value={payee} style={{ width: 200 }}
            onChange={(e) => setPayee(e.target.value)} />
          <Input aria-label={t('payables.account')} placeholder={t('payables.account')} value={account}
            style={{ width: 120 }} onChange={(e) => setAccount(e.target.value.toUpperCase())} />
        </>
      )}
      {kind === 'PREPAYMENT' && (
        <Input aria-label={t('payables.vendor')} placeholder={t('payables.vendor')} value={vendorCode}
          style={{ width: 140 }} onChange={(e) => setVendorCode(e.target.value.toUpperCase())} />
      )}
      <Input aria-label={t('payables.amount')} value={amount} style={{ width: 140, textAlign: 'right' }}
        placeholder={kind === 'BILL' ? t('payables.amountOpen') : t('payables.amount')} inputMode="decimal"
        data-testid="add-amount" onChange={(e) => setAmount(e.target.value)} onPressEnter={() => void add()} />
      {kind !== 'BILL' && (
        <Input aria-label={t('payables.description')} placeholder={t('payables.description')} value={description}
          style={{ width: 260 }} onChange={(e) => setDescription(e.target.value)} />
      )}
      <Button onClick={() => void add()} loading={busy} data-testid="add-line-button">{t('payables.addLine')}</Button>
    </Space>
  )
}

/** Submit, cancel, the approver's decision and the release, each when the run's state and the user allow it. */
function Actions({ loaded }: { loaded: LoadedRun }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const { message: toast } = App.useApp()
  const queryClient = useQueryClient()
  const { run } = loaded
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['fin', 'run', run.runId] })

  const act = async (name: string, process: string, input: Record<string, unknown>, done: string) => {
    setBusy(name)
    setError(null)
    try {
      await runProcess(process, input)
      toast.success(done)
      await refresh()
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(null)
    }
  }

  const open = run.status !== 'RELEASED' && run.status !== 'CANCELLED'
  const preparing = can(PERMISSIONS.payment)
  const deciding = run.status === 'SUBMITTED' && run.approvalRequestId && can(PERMISSIONS.decide)
  const releasing = run.status === 'APPROVED' && can(PERMISSIONS.release)
  if (!(open && preparing) && !deciding && !releasing) return null

  return (
    <Card size="small" title={t('payables.actions')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} data-testid="run-error" />}
        {deciding && (
          <ApprovalPanel requestId={run.approvalRequestId as string} onDecided={() => void refresh()} />
        )}
        <Space wrap>
          {run.status === 'DRAFT' && preparing && (
            <Button type="primary" loading={busy === 'submit'} disabled={busy !== null} data-testid="run-submit"
              onClick={() => void act('submit', PROCESSES.submit, { runId: run.runId }, t('payables.submitted'))}>
              {t('payables.submit')}
            </Button>
          )}
          {releasing && (
            <Popconfirm title={t('payables.releaseConfirm', { total: money(run.total) })} okText={t('payables.release')}
              onConfirm={() => void act('release', PROCESSES.release, { runId: run.runId }, t('payables.released'))}>
              <Button type="primary" danger loading={busy === 'release'} disabled={busy !== null} data-testid="run-release">
                {t('payables.release')}
              </Button>
            </Popconfirm>
          )}
          {open && preparing && (
            <>
              <Input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} style={{ width: 280 }}
                aria-label={t('payables.cancelReason')} placeholder={t('payables.cancelReason')} />
              <Button danger disabled={busy !== null || !reason.trim()} loading={busy === 'cancel'} data-testid="run-cancel"
                onClick={() => void act('cancel', PROCESSES.cancel, { runId: run.runId, reason: reason.trim() },
                  t('payables.cancelled'))}>
                {t('payables.cancelRun')}
              </Button>
            </>
          )}
        </Space>
      </Space>
    </Card>
  )
}

/** The payments a released run posted; who may void payments voids one on a day, with why. */
function PaymentsCard({ loaded }: { loaded: LoadedRun }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const [voiding, setVoiding] = useState<Payment | null>(null)
  const [voidDate, setVoidDate] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const onVoid = async () => {
    if (!voiding) return
    setBusy(true)
    setError(null)
    try {
      await runProcess(PROCESSES.voidPayment, { paymentId: voiding.paymentId, voidDate, reason: reason.trim() })
      setVoiding(null)
      setReason('')
      await queryClient.invalidateQueries({ queryKey: ['fin', 'run', loaded.run.runId] })
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card size="small" title={t('payables.paymentsTitle')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} data-testid="payment-error" />}
        <Table<Payment>
          size="small"
          rowKey="paymentId"
          pagination={false}
          dataSource={loaded.payments}
          data-testid="run-payments"
          columns={[
            { title: t('payables.paymentNo'), dataIndex: 'paymentNo' },
            { title: t('payables.vendor'), dataIndex: 'vendorCode' },
            { title: t('payables.payee'), dataIndex: 'payee' },
            { title: t('payables.checkNo'), dataIndex: 'checkNo' },
            { title: t('payables.amount'), dataIndex: 'amount', align: 'right', render: money },
            { title: t('payables.discount'), dataIndex: 'discount', align: 'right',
              render: (v: unknown) => (v === null || v === undefined ? '' : money(v)) },
            { title: t('payables.status'), dataIndex: 'status',
              render: (v: string, row) => (v === 'VOID'
                ? <Tag color="error">{t('payables.voidedOn', { date: row.voidDate ? formatDate(row.voidDate) : '' })}</Tag>
                : <Tag color="success">{t('payables.paymentStatuses.POSTED')}</Tag>) },
            ...(can(PERMISSIONS.voidPayment) ? [{
              title: '',
              key: 'void',
              render: (_: unknown, row: Payment) => row.status === 'POSTED' && (
                <Button size="small" danger onClick={() => setVoiding(row)} data-testid={`void-${row.paymentNo}`}>
                  {t('payables.voidPayment')}
                </Button>
              ),
            }] : []),
          ]}
        />
        {voiding && (
          <Space wrap data-testid="void-payment">
            <Typography.Text>{t('payables.voidingPayment', { number: voiding.paymentNo })}</Typography.Text>
            <Input type="date" value={voidDate} onChange={(e) => setVoidDate(e.target.value)}
              aria-label={t('payables.voidDate')} style={{ width: 170 }} />
            <Input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} style={{ width: 280 }}
              aria-label={t('payables.voidReason')} placeholder={t('payables.voidReason')} />
            <Button danger loading={busy} disabled={!voidDate || !reason.trim()} onClick={() => void onVoid()}
              data-testid="void-payment-confirm">
              {t('payables.voidPayment')}
            </Button>
            <Button onClick={() => setVoiding(null)}>{t('payables.close')}</Button>
          </Space>
        )}
      </Space>
    </Card>
  )
}

/**
 * The files given to the bank (FIN-AP-013, FIN-BK-011): made from the released run, kept as made with their hash,
 * downloaded by who releases payments; one active file of a kind, another made only after the first is cancelled with
 * why.
 */
function FilesCard({ loaded }: { loaded: LoadedRun }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const { run } = loaded
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState<PaymentFile | null>(null)
  const [reason, setReason] = useState('')
  if (!can(PERMISSIONS.release)) return null
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['fin', 'run', run.runId] })

  const generate = async (kind: FileKind) => {
    setBusy(kind)
    setError(null)
    try {
      await runProcess(PROCESSES.generate, { runId: run.runId, fileKind: kind })
      await refresh()
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(null)
    }
  }

  const download = async (file: PaymentFile) => {
    setError(null)
    try {
      const { blob, fileName } = await downloadGeneratedFile(file.generatedFileId)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = fileName ?? file.fileName
      link.click()
      // Some browsers start the download only after the click returns.
      setTimeout(() => URL.revokeObjectURL(url), 0)
    } catch (e) {
      setError(message(e))
    }
  }

  const cancel = async () => {
    if (!cancelling) return
    setBusy('cancel')
    setError(null)
    try {
      await runProcess(PROCESSES.cancelFile, { paymentFileId: cancelling.paymentFileId, reason: reason.trim() })
      setCancelling(null)
      setReason('')
      await refresh()
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(null)
    }
  }

  return (
    <Card size="small" title={t('payables.filesTitle')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} data-testid="file-error" />}
        <Space wrap>
          {FILE_KINDS[run.method].map((kind) => (
            <Button key={kind} onClick={() => void generate(kind)} loading={busy === kind} disabled={busy !== null}
              data-testid={`generate-${kind}`}>
              {t('payables.generate', { kind: t(`payables.fileKinds.${kind}`) })}
            </Button>
          ))}
        </Space>
        <Table<PaymentFile>
          size="small"
          rowKey="paymentFileId"
          pagination={false}
          dataSource={loaded.files}
          data-testid="run-files"
          locale={{ emptyText: t('payables.noFiles') }}
          columns={[
            { title: t('payables.fileName'), dataIndex: 'fileName' },
            { title: t('payables.fileKind'), dataIndex: 'fileKind', render: (v: string) => t(`payables.fileKinds.${v}`, v) },
            { title: t('payables.entries'), dataIndex: 'entryCount', align: 'right' },
            { title: t('payables.total'), dataIndex: 'total', align: 'right', render: money },
            { title: 'SHA-256', dataIndex: 'sha256', ellipsis: true },
            { title: t('payables.status'), dataIndex: 'status',
              render: (v: string, row) => (v === 'ACTIVE' ? <Tag color="success">{t('payables.fileStatuses.ACTIVE')}</Tag>
                : <Tag title={row.cancelReason ?? ''}>{t('payables.fileStatuses.CANCELLED')}</Tag>) },
            {
              title: '',
              key: 'actions',
              render: (_: unknown, row: PaymentFile) => (
                <Space>
                  {can(PERMISSIONS.generatedRead) && (
                    <Button size="small" onClick={() => void download(row)} data-testid={`download-${row.fileKind}`}>
                      {t('payables.download')}
                    </Button>
                  )}
                  {row.status === 'ACTIVE' && (
                    <Button size="small" danger onClick={() => setCancelling(row)}>{t('payables.cancelFile')}</Button>
                  )}
                </Space>
              ),
            },
          ]}
        />
        {cancelling && (
          <Space wrap>
            <Typography.Text>{t('payables.cancellingFile', { name: cancelling.fileName })}</Typography.Text>
            <Input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} style={{ width: 320 }}
              aria-label={t('payables.cancelReason')} placeholder={t('payables.cancelReason')} />
            <Button danger loading={busy === 'cancel'} disabled={!reason.trim()} onClick={() => void cancel()}>
              {t('payables.cancelFile')}
            </Button>
            <Button onClick={() => setCancelling(null)}>{t('payables.close')}</Button>
          </Space>
        )}
      </Space>
    </Card>
  )
}
