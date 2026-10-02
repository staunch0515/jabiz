import { api, runProcess, runQuery, sessionFetch, unwrap, ApiError } from '@jabiz/admin'
import { queryDataset } from '../journal/api'

/** The finance backend's names (backend/finance, ap/BillEntities, PaymentEntities, PaymentProcesses, PaymentFiles). */
export const DATASETS = {
  bill: 'urn:jabiz:dataset:default:FinBill',
  line: 'urn:jabiz:dataset:default:FinBillLine',
  tax: 'urn:jabiz:dataset:default:FinBillTax',
  application: 'urn:jabiz:dataset:default:FinApApplication',
  vendor: 'urn:jabiz:dataset:default:FinVendor',
  vendorBank: 'urn:jabiz:dataset:default:FinVendorBankAccount',
  taxCode: 'urn:jabiz:dataset:default:FinTaxCode',
  settings: 'urn:jabiz:dataset:default:FinApSettings',
  bank: 'urn:jabiz:dataset:default:FinBankAccount',
  run: 'urn:jabiz:dataset:default:FinPaymentRun',
  runLine: 'urn:jabiz:dataset:default:FinPaymentLine',
  payment: 'urn:jabiz:dataset:default:FinPayment',
  file: 'urn:jabiz:dataset:default:FinPaymentFile',
} as const

export const PROCESSES = {
  save: 'FIN_BILL_SAVE',
  delete: 'FIN_BILL_DELETE',
  post: 'FIN_BILL_POST',
  void: 'FIN_BILL_VOID',
  propose: 'FIN_PAYMENT_RUN_PROPOSE',
  add: 'FIN_PAYMENT_RUN_ADD',
  remove: 'FIN_PAYMENT_RUN_REMOVE',
  submit: 'FIN_PAYMENT_RUN_SUBMIT',
  cancel: 'FIN_PAYMENT_RUN_CANCEL',
  release: 'FIN_PAYMENT_RUN_RELEASE',
  voidPayment: 'FIN_PAYMENT_VOID',
  generate: 'FIN_PAYMENT_FILE_GENERATE',
  cancelFile: 'FIN_PAYMENT_FILE_CANCEL',
} as const

export const QUERIES = {
  billRegister: 'finance.ap.bill_register',
  runRegister: 'finance.ap.payment_run_register',
  paymentRegister: 'finance.ap.payment_register',
  aging: 'finance.ap.aging',
  statement: 'finance.ap.vendor_statement',
  form1099: 'finance.ap.form_1099',
  review1099: 'finance.ap.form_1099_review',
} as const

export const PERMISSIONS = {
  read: 'fin.ap.read',
  prepare: 'fin.bill.prepare',
  voidBill: 'fin.bill.void',
  vendor: 'fin.vendor.maintain',
  payment: 'fin.payment.prepare',
  release: 'fin.payment.release',
  voidPayment: 'fin.payment.void',
  settings: 'fin.ap.settings',
  bank: 'fin.bank.read',
  form1099: 'fin.1099.maintain',
  generatedRead: 'file.generated.read',
  decide: 'approval.decide',
} as const

export type BillKind = 'BILL' | 'CREDIT'
export type BillStatus = 'DRAFT' | 'POSTED' | 'VOID'
export type Approval = 'NOT_REQUIRED' | 'PENDING' | 'APPROVED' | 'REJECTED'
export type RunStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'RELEASED' | 'CANCELLED'
export type Method = 'ACH' | 'CHECK' | 'WIRE' | 'MANUAL' | 'CARD'
export type LineKind = 'BILL' | 'OTHER' | 'PREPAYMENT'
export type FileKind = 'NACHA' | 'CHECKS' | 'POSITIVE_PAY' | 'WIRE'
export const METHODS: Method[] = ['ACH', 'CHECK', 'WIRE', 'MANUAL', 'CARD']
/** The files a released run of each method gives the bank (PaymentFiles). */
export const FILE_KINDS: Record<Method, FileKind[]> = {
  ACH: ['NACHA'],
  CHECK: ['CHECKS', 'POSITIVE_PAY'],
  WIRE: ['WIRE'],
  MANUAL: [],
  CARD: [],
}

type Amount = number | string

/** A FinBill as the dataset returns it. */
export interface Bill {
  billId: string
  kind: BillKind
  billNo?: string | null
  vendorCode: string
  vendorInvoiceNo: string
  invoiceDate: string
  receivedDate?: string | null
  dueDate?: string | null
  currency: string
  termsCode?: string | null
  description?: string | null
  originalBillId?: string | null
  attachmentFileId?: string | null
  duplicateReason?: string | null
  source: string
  status: BillStatus
  approval?: Approval | null
  approvalRequestId?: string | null
  preparedBy?: string | null
  subtotal?: Amount | null
  useTaxTotal?: Amount | null
  total?: Amount | null
  openAmount?: Amount | null
  glNo?: string | null
  voidDate?: string | null
  voidReason?: string | null
}

export interface StoredBillLine {
  lineNo: Amount
  description: string
  amount: Amount
  account: string
  useTaxCode?: string | null
  department?: string | null
  location?: string | null
  form1099?: string | null
  box1099?: string | null
}

/** How a line's use tax came about (FinBillTax, FIN-TX-007, FIN-UI-007). */
export interface BillTax {
  taxId: string
  lineNo?: Amount | null
  jurisdiction?: string | null
  taxCode?: string | null
  base: Amount
  ratePercent?: Amount | null
  rateFrom?: string | null
  tax: Amount
}

export interface ApApplication {
  applicationId: string
  sourceKind: string
  sourceId: string
  sourceNo?: string | null
  billId: string
  vendorCode: string
  applicationDate: string
  amount: Amount
  discount?: Amount | null
  reversesApplicationId?: string | null
  reason?: string | null
}

export interface Vendor {
  vendorCode: string
  legalName: string
  currency: string
  termsCode: string
  expenseAccount?: string | null
  paymentMethod?: string | null
  form1099?: string | null
  box1099?: string | null
  status: string
}

export interface TaxCode {
  taxCode: string
  description: string
  kind: string
  active: boolean
}

export interface BillRegisterRow {
  billId: string
  billNo?: string | null
  kind: BillKind
  vendorCode: string
  vendorName?: string | null
  vendorInvoiceNo: string
  invoiceDate: string
  dueDate?: string | null
  currency?: string | null
  total?: Amount | null
  useTaxTotal?: Amount | null
  openAmount?: Amount | null
  /** In US dollars at the bill's rate (F7); a bill from before F7 is in dollars. */
  totalUsd?: Amount | null
  openAmountUsd?: Amount | null
  status: BillStatus
  approval?: Approval | null
  glNo?: string | null
}

export interface LineInput {
  description: string
  amount: string
  account?: string | null
  useTaxCode?: string | null
  department?: string | null
  form1099?: string | null
  box1099?: string | null
  location?: string | null
}

export interface BillInput {
  billId?: string
  kind: BillKind
  vendorCode: string
  vendorInvoiceNo: string
  invoiceDate: string
  receivedDate?: string | null
  termsCode?: string | null
  description?: string | null
  originalBillId?: string | null
  attachmentFileId?: string | null
  duplicateReason?: string | null
  lines: LineInput[]
}

export interface BillOutput {
  billId: string
  billNo?: string | null
  kind: BillKind
  status: BillStatus
  dueDate?: string | null
  total?: Amount | null
  useTaxTotal?: Amount | null
  openAmount?: Amount | null
  glNo?: string | null
  approval?: Approval | null
  approvalRequestId?: string | null
  warnings?: string[] | null
  assets?: string[] | null
}

export interface PaymentRun {
  runId: string
  runNo: string
  bankCode: string
  paymentDate: string
  method: Method
  description?: string | null
  status: RunStatus
  total?: Amount | null
  lineCount?: Amount | null
  preparedBy?: string | null
  approvalRequestId?: string | null
  approvedBy?: string | null
  releasedBy?: string | null
  cancelReason?: string | null
}

export interface RunLine {
  lineId: string
  runId: string
  kind: LineKind
  billId?: string | null
  billNo?: string | null
  vendorCode?: string | null
  payee?: string | null
  account?: string | null
  description?: string | null
  amount: Amount
  discount?: Amount | null
}

export interface Payment {
  paymentId: string
  paymentNo: string
  runId: string
  runNo: string
  kind: LineKind
  vendorCode?: string | null
  payee: string
  method: Method
  paymentDate: string
  amount: Amount
  discount?: Amount | null
  checkNo?: string | null
  openAmount?: Amount | null
  status: 'POSTED' | 'VOID'
  voidDate?: string | null
  voidReason?: string | null
}

export interface PaymentFile {
  paymentFileId: string
  runId: string
  fileKind: FileKind
  fileName: string
  generatedFileId: string
  sha256: string
  entryCount?: Amount | null
  total?: Amount | null
  status: 'ACTIVE' | 'CANCELLED'
  generatedBy: string
  cancelReason?: string | null
}

/** A bill held out of a proposal, and why (PaymentProcesses.Held). */
export interface Held {
  billId: string
  billNo: string
  vendorCode: string
  amount: Amount
  reason: string
}

export interface RunOutput {
  runId: string
  runNo: string
  status: RunStatus
  total?: Amount | null
  lineCount: number
  approvalRequestId?: string | null
  held: Held[]
  payments: { paymentId: string; paymentNo: string; vendorCode?: string | null; payee: string; amount: Amount;
    checkNo?: string | null }[]
}

export interface ProposeInput {
  bankCode?: string | null
  paymentDate: string
  method: Method
  dueThrough?: string | null
  vendorCodes?: string[] | null
  takeDiscounts?: boolean
  description?: string | null
  /** The bills' currency; US dollars when absent (F7 plan decision D4). */
  currency?: string | null
  /** The rate the bank pays a foreign currency at; the payment day's spot rate when absent. */
  exchangeRate?: string | null
}

export interface RunRegisterRow {
  runId: string
  runNo: string
  paymentDate: string
  method: Method
  bankCode: string
  status: RunStatus
  total?: Amount | null
  lineCount?: Amount | null
  description?: string | null
  preparedBy?: string | null
  approvedBy?: string | null
  releasedBy?: string | null
}

export interface PaymentRegisterRow {
  paymentId: string
  paymentNo: string
  paymentDate: string
  runId: string
  runNo: string
  kind: LineKind
  vendorCode?: string | null
  payee: string
  method: Method
  checkNo?: string | null
  currency?: string | null
  amount: Amount
  /** What the bank paid, in US dollars (F7). */
  amountUsd?: Amount | null
  discount?: Amount | null
  status: 'POSTED' | 'VOID'
  voidDate?: string | null
}

const PAGE = 500

async function entity<T>(datasetId: string, id: string): Promise<{ value: T; version: number }> {
  const instance = await unwrap(
    api.GET('/api/datasets/{resourceId}/entities/{id}', { params: { path: { resourceId: datasetId, id } } }),
  )
  return { value: instance.attributes as unknown as T, version: Number(instance.version ?? 0) }
}

export async function loadVendors(): Promise<Vendor[]> {
  const vendors = await queryDataset<Vendor>(DATASETS.vendor, [])
  return vendors.sort((a, b) => a.vendorCode.localeCompare(b.vendorCode))
}

/** The use tax codes: tax codes a purchase can accrue (the payables use the receivables' table, F4b). */
export async function loadTaxCodes(): Promise<TaxCode[]> {
  const codes = await queryDataset<TaxCode>(DATASETS.taxCode, [{ field: 'active', op: 'eq', value: true }])
  return codes.sort((a, b) => a.taxCode.localeCompare(b.taxCode))
}

export interface LoadedBill {
  bill: Bill
  version: number
  lines: StoredBillLine[]
  taxes: BillTax[]
  applications: ApApplication[]
}

export async function loadBill(billId: string): Promise<LoadedBill> {
  const filter = [{ field: 'billId', op: 'eq', value: billId }]
  const [bill, lines, taxes, applications] = await Promise.all([
    entity<Bill>(DATASETS.bill, billId),
    queryDataset<StoredBillLine>(DATASETS.line, filter),
    queryDataset<BillTax>(DATASETS.tax, filter),
    queryDataset<ApApplication>(DATASETS.application, filter),
  ])
  return {
    bill: bill.value,
    version: bill.version,
    lines: lines.sort((a, b) => Number(a.lineNo) - Number(b.lineNo)),
    taxes,
    applications: applications.sort((a, b) => a.applicationDate.localeCompare(b.applicationDate)),
  }
}

/** A bill by its number, for adding it to a payment run by what the clerk sees ("BILL-12", "P-7781"). */
export async function findBill(billNo: string): Promise<Bill | null> {
  const found = await queryDataset<Bill>(DATASETS.bill, [{ field: 'billNo', op: 'eq', value: billNo }])
  return found[0] ?? null
}

export function saveBill(input: BillInput, idempotencyKey?: string): Promise<BillOutput> {
  return runProcess<BillOutput>(PROCESSES.save, { ...input }, { idempotencyKey })
}

export function postBill(billId: string, idempotencyKey?: string): Promise<BillOutput> {
  return runProcess<BillOutput>(PROCESSES.post, { billId }, { idempotencyKey })
}

export function loadBillRegister(params: { from: string; to: string; status?: string | null;
  kind?: string | null; approval?: string | null; vendorCode?: string | null }) {
  return runQuery<BillRegisterRow>(QUERIES.billRegister, {
    params: { from: params.from, to: params.to, status: params.status ?? null, kind: params.kind ?? null,
      approval: params.approval ?? null, vendorCode: params.vendorCode || null },
    limit: PAGE,
    count: true,
  })
}

export function loadRunRegister(params: { from: string; to: string; status?: string | null }) {
  return runQuery<RunRegisterRow>(QUERIES.runRegister, {
    params: { from: params.from, to: params.to, status: params.status ?? null },
    limit: PAGE,
    count: true,
  })
}

export function loadPaymentRegister(params: { from: string; to: string; status?: string | null;
  method?: string | null; vendorCode?: string | null }) {
  return runQuery<PaymentRegisterRow>(QUERIES.paymentRegister, {
    params: { from: params.from, to: params.to, status: params.status ?? null, method: params.method ?? null,
      vendorCode: params.vendorCode || null },
    limit: PAGE,
    count: true,
  })
}

export interface LoadedRun {
  run: PaymentRun
  lines: RunLine[]
  payments: Payment[]
  files: PaymentFile[]
}

export async function loadRun(runId: string): Promise<LoadedRun> {
  const filter = [{ field: 'runId', op: 'eq', value: runId }]
  const [run, lines, payments, files] = await Promise.all([
    entity<PaymentRun>(DATASETS.run, runId),
    queryDataset<RunLine>(DATASETS.runLine, filter),
    queryDataset<Payment>(DATASETS.payment, filter),
    queryDataset<PaymentFile>(DATASETS.file, filter),
  ])
  return {
    run: run.value,
    lines: lines.sort((a, b) => `${a.vendorCode ?? ''}${a.billNo ?? ''}`.localeCompare(`${b.vendorCode ?? ''}${b.billNo ?? ''}`)),
    payments: payments.sort((a, b) => a.paymentNo.localeCompare(b.paymentNo)),
    files: files.sort((a, b) => a.fileName.localeCompare(b.fileName)),
  }
}

export function proposeRun(input: ProposeInput, idempotencyKey?: string): Promise<RunOutput> {
  return runProcess<RunOutput>(PROCESSES.propose, { ...input }, { idempotencyKey })
}

/** The payables settings' default bank account, offered when proposing a run. */
export async function loadDefaultBank(): Promise<string | null> {
  const settings = await queryDataset<{ defaultBank?: string | null }>(DATASETS.settings, [])
  return settings[0]?.defaultBank ?? null
}

/** A file kept by the platform's archive, as it was given to the bank (GET /api/generated-files/{id}). */
export async function downloadGeneratedFile(fileId: string): Promise<{ blob: Blob; fileName?: string }> {
  const response = await sessionFetch(`/api/generated-files/${encodeURIComponent(fileId)}`)
  if (!response.ok) {
    const problem = (await response.json().catch(() => undefined)) as Record<string, unknown> | undefined
    throw new ApiError(response.status, problem ?? { title: response.statusText })
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const name = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1] ?? /filename="?([^";]+)"?/i.exec(disposition)?.[1]
  return { blob: await response.blob(), fileName: name ? decodeURIComponent(name.trim()) : undefined }
}
