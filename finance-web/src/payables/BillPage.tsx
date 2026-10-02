import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, ApprovalPanel, EXTENSION_NAMESPACE, runProcess, useAuth } from '@jabiz/admin'
import { Alert, App, Button, Card, Input, Space, Tag, Typography } from 'antd'
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { loadAccounts, loadDimensions } from '../journal/api'
import Field from '../receivables/Field'
import {
  loadBill,
  loadTaxCodes,
  loadVendors,
  PERMISSIONS,
  postBill,
  PROCESSES,
  saveBill,
  type Bill,
  type BillInput,
  type BillKind,
  type BillOutput,
} from './api'
import {
  checkLine,
  fromStored,
  hasProblems,
  isBlank,
  padLines,
  placeViolations,
  toInputs,
  type BillLine,
  type ServerProblems,
} from './bill'
import BillLines from './BillLines'
import BillView from './BillView'
import { billPath, BILLS_PATH } from './paths'
import { BillStatusTag } from './StatusTags'

interface Header {
  kind: BillKind
  vendorCode: string
  vendorInvoiceNo: string
  invoiceDate: string
  receivedDate: string
  termsCode: string
  description: string
  duplicateReason: string
  // Not shown here but kept: the server writes back what a save sends, and a credit's original bill may not change.
  originalBillId: string | null
  attachmentFileId: string | null
}

const EMPTY_HEADER: Header = {
  kind: 'BILL',
  vendorCode: '',
  vendorInvoiceNo: '',
  invoiceDate: '',
  receivedDate: '',
  termsCode: '',
  description: '',
  duplicateReason: '',
  originalBillId: null,
  attachmentFileId: null,
}

function headerOf(bill: Bill): Header {
  return {
    kind: bill.kind,
    vendorCode: bill.vendorCode,
    vendorInvoiceNo: bill.vendorInvoiceNo,
    invoiceDate: bill.invoiceDate,
    receivedDate: bill.receivedDate ?? '',
    termsCode: bill.termsCode ?? '',
    description: bill.description ?? '',
    duplicateReason: bill.duplicateReason ?? '',
    originalBillId: bill.originalBillId ?? null,
    attachmentFileId: bill.attachmentFileId ?? null,
  }
}

/**
 * A vendor bill or credit (FIN-AP-004, 005, 006; FIN-UI-002): a draft is entered and changed here by keyboard alone
 * and saved through FIN_BILL_SAVE, then posted through FIN_BILL_POST, which accrues use tax, numbers and books it.
 * Ctrl+S saves, Ctrl+Enter posts. A bill like another of the vendor's (same amount and date) is saved only with a
 * reason, asked for when the server says so. A posted document is shown read-only ({@link BillView}).
 */
export default function BillPage({ instanceKey }: { instanceKey?: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { message } = App.useApp()
  const { can } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { billId } = useParams<{ billId: string }>()
  const [search] = useSearchParams()
  const formId = useId()

  const vendors = useQuery({ queryKey: ['fin', 'vendors'], queryFn: loadVendors, staleTime: 60_000 })
  const taxCodes = useQuery({ queryKey: ['fin', 'taxCodes'], queryFn: loadTaxCodes, staleTime: 60_000 })
  const accounts = useQuery({ queryKey: ['fin', 'accounts'], queryFn: loadAccounts, staleTime: 60_000 })
  const dimensions = useQuery({ queryKey: ['fin', 'dimensions'], queryFn: loadDimensions, staleTime: 60_000 })
  const loaded = useQuery({
    queryKey: ['fin', 'bill', billId],
    queryFn: () => loadBill(billId as string),
    enabled: Boolean(billId),
    // An approval decided comes back through the platform's events a moment later: look again until then.
    refetchInterval: (query) => (query.state.data?.bill.approval === 'PENDING' ? 5_000 : false),
  })
  const bill = loaded.data?.bill
  const [startedNew] = useState(!billId)

  const [header, setHeader] = useState<Header>(() => ({
    ...EMPTY_HEADER, kind: search.get('kind') === 'CREDIT' ? 'CREDIT' : 'BILL' }))
  const [lines, setLines] = useState<BillLine[]>(() => padLines([], 2))
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState<'save' | 'post' | 'delete' | null>(null)
  const [server, setServer] = useState<ServerProblems | null>(null)
  const [warnings, setWarnings] = useState<string[]>([])
  const idempotency = useRef<{ key: string; body: string } | null>(null)

  const shown = useRef<string | null>(null)
  useEffect(() => {
    if (!loaded.data || dirty) return
    const stamp = `${loaded.data.bill.billId}:${loaded.data.version}`
    if (shown.current === stamp) return
    shown.current = stamp
    setHeader(headerOf(loaded.data.bill))
    setLines(padLines(fromStored(loaded.data.lines), 1))
  }, [loaded.data, dirty])

  const vendor = vendors.data?.find((v) => v.vendorCode === header.vendorCode.trim())
  const taxCodeSet = useMemo(() => new Set((taxCodes.data ?? []).map((c) => c.taxCode)), [taxCodes.data])
  const problems = useMemo(() => lines.map((line) => checkLine(line, { accounts: accounts.data,
    taxCodes: taxCodeSet, departments: dimensions.data?.departments })), [lines, accounts.data, taxCodeSet,
    dimensions.data])
  const editable = (!bill || bill.status === 'DRAFT') && can(PERMISSIONS.prepare)
  const askReason = Boolean(server?.possibleDuplicate || header.duplicateReason)

  const changeHeader = (patch: Partial<Header>) => {
    setHeader((current) => ({ ...current, ...patch }))
    setDirty(true)
    setServer(null)
  }
  const changeLines = (next: BillLine[]) => {
    setLines(next)
    setDirty(true)
    setServer(null)
  }

  const keyFor = (body: unknown) => {
    const text = JSON.stringify(body)
    if (idempotency.current?.body !== text) idempotency.current = { key: crypto.randomUUID(), body: text }
    return idempotency.current.key
  }

  const showRefusal = (error: unknown, rows: number[]) => {
    if (error instanceof ApiError) {
      const placed = placeViolations(error.violations, rows)
      if (placed.general.length === 0 && placed.cells.size === 0) placed.general.push(error.display)
      setServer(placed)
    } else {
      setServer({ cells: new Map(), general: [String(error)], possibleDuplicate: false })
    }
  }

  const save = async (): Promise<BillOutput | null> => {
    const { inputs, rows } = toInputs(lines)
    const input: BillInput = {
      billId: billId ?? undefined,
      kind: header.kind,
      vendorCode: header.vendorCode.trim(),
      vendorInvoiceNo: header.vendorInvoiceNo.trim(),
      invoiceDate: header.invoiceDate,
      receivedDate: header.receivedDate || null,
      termsCode: header.termsCode.trim() || null,
      description: header.description.trim() || null,
      originalBillId: header.originalBillId,
      attachmentFileId: header.attachmentFileId,
      duplicateReason: header.duplicateReason.trim() || null,
      lines: inputs,
    }
    try {
      const output = await saveBill(input, keyFor({ save: input }))
      idempotency.current = null
      setLines((current) => padLines(current.filter((line) => !isBlank(line)), 1))
      setDirty(false)
      setServer(null)
      setWarnings(output.warnings ?? [])
      shown.current = null
      await queryClient.invalidateQueries({ queryKey: ['fin', 'bill', output.billId] })
      if (!billId) navigate(billPath(output.billId), { replace: true, state: { pageInstance: instanceKey } })
      return output
    } catch (error) {
      showRefusal(error, rows)
      return null
    }
  }

  const onSave = async () => {
    if (busy || !editable) return
    setBusy('save')
    try {
      if (await save()) message.success(t('payables.saved'))
    } finally {
      setBusy(null)
    }
  }

  const onPost = async () => {
    if (busy || !editable) return
    setBusy('post')
    try {
      let id = billId
      let rows = toInputs(lines).rows
      if (!id || dirty) {
        const saved = await save()
        if (!saved) return
        id = saved.billId
        rows = toInputs(lines).inputs.map((_, i) => i)
      }
      try {
        const output = await postBill(id as string, keyFor({ post: id }))
        idempotency.current = null
        setWarnings(output.warnings ?? [])
        message.success(output.approval === 'PENDING' ? t('payables.postedPending', { number: output.billNo })
          : t('payables.posted', { number: output.billNo }))
        shown.current = null
        await queryClient.cancelQueries({ queryKey: ['fin', 'bill', id] })
        await queryClient.invalidateQueries({ queryKey: ['fin', 'bill', id] })
      } catch (error) {
        showRefusal(error, rows)
      }
    } finally {
      setBusy(null)
    }
  }

  const onDelete = async () => {
    if (!billId || busy) return
    setBusy('delete')
    try {
      await runProcess(PROCESSES.delete, { billId })
      message.success(t('payables.deleted'))
      navigate(BILLS_PATH)
    } catch (error) {
      showRefusal(error, [])
    } finally {
      setBusy(null)
    }
  }

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const ctrl = event.ctrlKey || event.metaKey
    if (ctrl && event.key.toLowerCase() === 's') {
      event.preventDefault()
      void onSave()
    } else if (ctrl && event.key === 'Enter') {
      event.preventDefault()
      void onPost()
    }
  }

  if (billId && loaded.isLoading && !startedNew) return <Card loading />
  if (billId && loaded.error) {
    return <Alert type="error" showIcon message={loaded.error instanceof ApiError ? loaded.error.display : String(loaded.error)} />
  }
  if (bill && loaded.data && bill.status !== 'DRAFT') {
    return <BillView loaded={loaded.data} warnings={warnings} />
  }

  const credit = header.kind === 'CREDIT'
  const title = t(credit ? 'payables.titleNewCredit' : 'payables.titleNewBill')

  return (
    <div onKeyDown={onKeyDown} data-testid="bill-page">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space align="center" wrap>
          <Link to={BILLS_PATH}>{t('payables.backToBills')}</Link>
          <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{title}</Typography.Title>
          {bill && <BillStatusTag status={bill.status} />}
          {dirty && <Tag>{t('payables.unsaved')}</Tag>}
        </Space>

        {bill?.approval === 'PENDING' && bill.approvalRequestId && can(PERMISSIONS.decide) && (
          <Card size="small" title={t('payables.facts.approval')}>
            <ApprovalPanel requestId={bill.approvalRequestId}
              onDecided={() => void queryClient.invalidateQueries({ queryKey: ['fin', 'bill', billId] })} />
          </Card>
        )}

        <Card size="small">
          <datalist id={`${formId}-vendors`}>
            {(vendors.data ?? []).filter((v) => v.status === 'ACTIVE').map((v) => (
              <option key={v.vendorCode} value={v.vendorCode}>{v.legalName}</option>
            ))}
          </datalist>
          <Space wrap size="large" align="end">
            <Field id={`${formId}-vendor`} label={t('payables.vendor')}>
              <Input id={`${formId}-vendor`} list={`${formId}-vendors`} value={header.vendorCode}
                disabled={!editable || Boolean(bill)} style={{ width: 150 }} autoFocus={!billId}
                data-testid="bill-vendor" onChange={(e) => changeHeader({ vendorCode: e.target.value.toUpperCase() })} />
            </Field>
            <Typography.Text data-testid="bill-vendor-name" style={{ minWidth: 160 }}>{vendor?.legalName ?? ''}</Typography.Text>
            <Field id={`${formId}-number`} label={t('payables.vendorInvoiceNo')}>
              <Input id={`${formId}-number`} value={header.vendorInvoiceNo} disabled={!editable} maxLength={40}
                style={{ width: 170 }} data-testid="bill-number"
                onChange={(e) => changeHeader({ vendorInvoiceNo: e.target.value })} />
            </Field>
            <Field id={`${formId}-date`} label={t('payables.invoiceDate')}>
              <Input id={`${formId}-date`} type="date" value={header.invoiceDate} disabled={!editable}
                style={{ width: 170 }} data-testid="bill-date" onChange={(e) => changeHeader({ invoiceDate: e.target.value })} />
            </Field>
            <Field id={`${formId}-received`} label={t('payables.receivedDate')}>
              <Input id={`${formId}-received`} type="date" value={header.receivedDate} disabled={!editable}
                style={{ width: 170 }} onChange={(e) => changeHeader({ receivedDate: e.target.value })} />
            </Field>
            {!credit && (
              <Field id={`${formId}-terms`} label={t('payables.terms')}>
                <Input id={`${formId}-terms`} value={header.termsCode} disabled={!editable} maxLength={20}
                  style={{ width: 140 }} placeholder={vendor ? t('payables.fromVendor', { value: vendor.termsCode }) : undefined}
                  onChange={(e) => changeHeader({ termsCode: e.target.value.toUpperCase() })} />
              </Field>
            )}
            <Field id={`${formId}-description`} label={t('payables.description')} grow>
              <Input id={`${formId}-description`} value={header.description} disabled={!editable} maxLength={500}
                onChange={(e) => changeHeader({ description: e.target.value })} />
            </Field>
          </Space>
          {askReason && (
            <div style={{ marginTop: 12 }}>
              <Field id={`${formId}-reason`} label={t('payables.duplicateReason')} grow>
                <Input id={`${formId}-reason`} value={header.duplicateReason} disabled={!editable} maxLength={500}
                  data-testid="bill-duplicate-reason" onChange={(e) => changeHeader({ duplicateReason: e.target.value })} />
              </Field>
            </div>
          )}
        </Card>

        {server && server.general.length > 0 && (
          <Alert type="error" showIcon data-testid="bill-problems" message={t('payables.refused')}
            description={<ul style={{ margin: 0, paddingLeft: 18 }}>{server.general.map((text) => <li key={text}>{text}</li>)}</ul>} />
        )}
        {warnings.map((text) => <Alert key={text} type="warning" showIcon message={text} />)}

        <Card size="small" title={t('payables.lines')}>
          <BillLines
            lines={lines}
            onChange={changeLines}
            problems={problems}
            serverProblems={server?.cells}
            accounts={[...(accounts.data?.values() ?? [])].filter((a) => a.active && !a.summary)}
            taxCodes={taxCodes.data ?? []}
            departments={[...(dimensions.data?.departments ?? [])].sort()}
            readOnly={!editable}
          />
          <Typography.Text type="secondary">
            {vendor?.expenseAccount ? t('payables.defaultsHint', { account: vendor.expenseAccount,
              form: vendor.form1099 ? `1099-${vendor.form1099} ${t('payables.box')} ${vendor.box1099}` : t('payables.none') })
              : t('payables.totalHint')}
          </Typography.Text>
        </Card>

        {editable && (
          <Space wrap>
            <Button onClick={() => void onSave()} loading={busy === 'save'} disabled={busy !== null} title="Ctrl+S"
              data-testid="bill-save">
              {t('payables.save')}
            </Button>
            <Button type="primary" onClick={() => void onPost()} loading={busy === 'post'}
              disabled={busy !== null || hasProblems(problems)} title="Ctrl+Enter" data-testid="bill-post">
              {t('payables.post')}
            </Button>
            {bill && (
              <Button danger onClick={() => void onDelete()} loading={busy === 'delete'} disabled={busy !== null}>
                {t('payables.delete')}
              </Button>
            )}
          </Space>
        )}
      </Space>
    </div>
  )
}
