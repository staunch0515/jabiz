import { api, runProcess, runQuery, unwrap } from '@jabiz/admin'
import { queryDataset } from '../journal/api'

/** The finance backend's names (backend/finance, ar/InvoiceEntities, ar/ReceiptProcesses, ar/InvoiceDocuments). */
export const DATASETS = {
  invoice: 'urn:jabiz:dataset:default:FinInvoice',
  line: 'urn:jabiz:dataset:default:FinInvoiceLine',
  tax: 'urn:jabiz:dataset:default:FinInvoiceTax',
  application: 'urn:jabiz:dataset:default:FinApplication',
  receipt: 'urn:jabiz:dataset:default:FinReceipt',
  customer: 'urn:jabiz:dataset:default:FinCustomer',
  taxCode: 'urn:jabiz:dataset:default:FinTaxCode',
  company: 'urn:jabiz:dataset:default:FinCompanyProfile',
} as const

export const PROCESSES = {
  save: 'FIN_INVOICE_SAVE',
  delete: 'FIN_INVOICE_DELETE',
  post: 'FIN_INVOICE_POST',
  void: 'FIN_INVOICE_VOID',
  issue: 'FIN_INVOICE_ISSUE',
  record: 'FIN_RECEIPT_RECORD',
  apply: 'FIN_RECEIPT_APPLY',
  reverse: 'FIN_APPLICATION_REVERSE',
} as const

export const QUERIES = {
  invoiceRegister: 'finance.ar.invoice_register',
  receiptRegister: 'finance.ar.receipt_register',
  suggestions: 'finance.ar.receipt_suggestions',
  aging: 'finance.ar.aging',
  statement: 'finance.ar.statement',
  salesTax: 'finance.tax.sales_tax',
  certificates: 'finance.ar.certificates',
} as const

/** The document layouts of invoices and credit memos (FIN-AR-005). */
export const LAYOUTS = { INVOICE: 'finance.ar.invoice', CREDIT_MEMO: 'finance.ar.credit_memo' } as const

export const PERMISSIONS = {
  read: 'fin.ar.read',
  prepare: 'fin.invoice.prepare',
  credit: 'fin.invoice.credit',
  issue: 'fin.invoice.issue',
  receipt: 'fin.receipt.record',
  adjust: 'fin.receipt.adjust',
  customer: 'fin.customer.maintain',
  company: 'fin.company.maintain',
  archive: 'document.archive.read',
  decide: 'approval.decide',
} as const

export type InvoiceKind = 'INVOICE' | 'CREDIT_MEMO'
export type InvoiceStatus = 'DRAFT' | 'POSTED' | 'VOID' | 'WRITTEN_OFF'
export type ReceiptStatus = 'POSTED' | 'VOID'
export const RECEIPT_METHODS = ['CHECK', 'ACH', 'WIRE', 'CARD'] as const

type Amount = number | string

/** A FinInvoice as the dataset returns it. */
export interface Invoice {
  invoiceId: string
  kind: InvoiceKind
  invoiceNo?: string | null
  customerCode: string
  invoiceDate: string
  dueDate?: string | null
  currency: string
  exchangeRate?: Amount | null
  termsCode: string
  taxCode: string
  description?: string | null
  reference?: string | null
  originalInvoiceId?: string | null
  source: string
  status: InvoiceStatus
  preparedBy?: string | null
  subtotal?: Amount | null
  taxTotal?: Amount | null
  total?: Amount | null
  totalUsd?: Amount | null
  openAmount?: Amount | null
  openAmountUsd?: Amount | null
  glNo?: string | null
  voidDate?: string | null
  voidReason?: string | null
  approval?: string | null
  approvalRequestId?: string | null
}

export interface StoredInvoiceLine {
  lineNo: Amount
  description: string
  quantity: Amount
  unitPrice: Amount
  amount?: Amount | null
  revenueAccount: string
  taxCode?: string | null
  department?: string | null
  location?: string | null
}

/** How a posted document's tax came about (FinInvoiceTax, FIN-UI-007). */
export interface InvoiceTax {
  taxId: string
  lineNo?: Amount | null
  jurisdiction?: string | null
  taxCode?: string | null
  taxKind?: string | null
  reason?: string | null
  certificateNo?: string | null
  base: Amount
  ratePercent?: Amount | null
  rateFrom?: string | null
  tax: Amount
}

export interface Application {
  applicationId: string
  sourceKind: string
  sourceId: string
  invoiceId: string
  customerCode: string
  applicationDate: string
  amount: Amount
  amountUsd: Amount
  reversesApplicationId?: string | null
  discount?: Amount | null
  sourceNo?: string | null
  reason?: string | null
}

export interface Receipt {
  receiptId: string
  receiptNo?: string | null
  customerCode: string
  receiptDate: string
  amount: Amount
  currency: string
  method: string
  reference?: string | null
  bankAccount: string
  description?: string | null
  unappliedAmount: Amount
  status: ReceiptStatus
  preparedBy?: string | null
  voidDate?: string | null
  voidReason?: string | null
}

export interface Customer {
  customerCode: string
  legalName: string
  currency: string
  termsCode: string
  taxCode: string
  status: string
  contactEmail?: string | null
}

export interface TaxCode {
  taxCode: string
  description: string
  kind: string
  active: boolean
}

/** A row of finance.ar.invoice_register: dollar columns signed, credit memos negative. */
export interface InvoiceRegisterRow {
  invoiceId: string
  invoiceNo?: string | null
  kind: InvoiceKind
  invoiceDate: string
  dueDate?: string | null
  customerCode: string
  customerName?: string | null
  reference?: string | null
  currency: string
  total?: Amount | null
  openAmount?: Amount | null
  totalUsd?: Amount | null
  openAmountUsd?: Amount | null
  status: InvoiceStatus
  glNo?: string | null
}

export interface ReceiptRegisterRow {
  receiptId: string
  receiptNo?: string | null
  receiptDate: string
  customerCode: string
  customerName?: string | null
  method: string
  reference?: string | null
  bankAccount: string
  currency: string
  amount: Amount
  unappliedAmount: Amount
  status: ReceiptStatus
}

/** An open invoice a receipt may pay (finance.ar.receipt_suggestions), best match first. */
export interface Suggestion {
  rank: Amount
  matched: string
  invoiceId: string
  invoiceNo: string
  invoiceDate: string
  dueDate?: string | null
  openAmount: Amount
  discountOffered?: Amount | null
  discountUntil?: string | null
}

export interface LineInput {
  description: string
  quantity: string
  unitPrice: string
  revenueAccount?: string | null
  taxCode?: string | null
}

export interface InvoiceInput {
  invoiceId?: string
  kind: InvoiceKind
  customerCode: string
  invoiceDate: string
  termsCode?: string | null
  taxCode?: string | null
  currency?: string | null
  description?: string | null
  reference?: string | null
  originalInvoiceId?: string | null
  lines: LineInput[]
}

export interface InvoiceOutput {
  invoiceId: string
  invoiceNo?: string | null
  kind: InvoiceKind
  status: InvoiceStatus
  dueDate?: string | null
  subtotal?: Amount | null
  taxTotal?: Amount | null
  total?: Amount | null
  openAmount?: Amount | null
  glNo?: string | null
  warnings?: string[] | null
  approval?: string | null
}

export interface ApplicationInput {
  invoiceId: string
  amount: string
  discount?: string | null
}

export interface ReceiptInput {
  customerCode: string
  receiptDate: string
  amount: string
  method: string
  reference?: string | null
  bankAccount: string
  description?: string | null
  applications: ApplicationInput[]
}

export interface ReceiptOutput {
  receiptId: string
  receiptNo?: string | null
  status: ReceiptStatus
  amount: Amount
  unappliedAmount: Amount
  glNo?: string | null
}

const PAGE = 500

async function entity<T>(datasetId: string, id: string): Promise<{ value: T; version: number }> {
  const instance = await unwrap(
    api.GET('/api/datasets/{resourceId}/entities/{id}', { params: { path: { resourceId: datasetId, id } } }),
  )
  return { value: instance.attributes as unknown as T, version: Number(instance.version ?? 0) }
}

export async function loadCustomers(): Promise<Customer[]> {
  const customers = await queryDataset<Customer>(DATASETS.customer, [])
  return customers.sort((a, b) => a.customerCode.localeCompare(b.customerCode))
}

export async function loadTaxCodes(): Promise<TaxCode[]> {
  const codes = await queryDataset<TaxCode>(DATASETS.taxCode, [{ field: 'active', op: 'eq', value: true }])
  return codes.sort((a, b) => a.taxCode.localeCompare(b.taxCode))
}

export interface LoadedInvoice {
  invoice: Invoice
  version: number
  lines: StoredInvoiceLine[]
  taxes: InvoiceTax[]
  applications: Application[]
}

export async function loadInvoice(invoiceId: string): Promise<LoadedInvoice> {
  const filter = [{ field: 'invoiceId', op: 'eq', value: invoiceId }]
  const [invoice, lines, taxes, applications] = await Promise.all([
    entity<Invoice>(DATASETS.invoice, invoiceId),
    queryDataset<StoredInvoiceLine>(DATASETS.line, filter),
    queryDataset<InvoiceTax>(DATASETS.tax, filter),
    queryDataset<Application>(DATASETS.application, filter),
  ])
  return {
    invoice: invoice.value,
    version: invoice.version,
    lines: lines.sort((a, b) => Number(a.lineNo) - Number(b.lineNo)),
    taxes,
    applications: applications.sort((a, b) => a.applicationDate.localeCompare(b.applicationDate)),
  }
}

export interface LoadedReceipt {
  receipt: Receipt
  applications: Application[]
}

export async function loadReceipt(receiptId: string): Promise<LoadedReceipt> {
  const [receipt, applications] = await Promise.all([
    entity<Receipt>(DATASETS.receipt, receiptId),
    queryDataset<Application>(DATASETS.application, [{ field: 'sourceId', op: 'eq', value: receiptId }]),
  ])
  return {
    receipt: receipt.value,
    applications: applications.sort((a, b) => a.applicationDate.localeCompare(b.applicationDate)),
  }
}

/** The invoice numbers of a receipt's applications, for showing them (a receipt pays a few invoices). */
export async function loadInvoiceNumbers(invoiceIds: string[]): Promise<Map<string, string>> {
  const ids = [...new Set(invoiceIds)]
  const invoices = await Promise.all(ids.map((id) => entity<Invoice>(DATASETS.invoice, id)))
  return new Map(invoices.map(({ value }) => [value.invoiceId, value.invoiceNo ?? '']))
}

export function saveInvoice(input: InvoiceInput, idempotencyKey?: string): Promise<InvoiceOutput> {
  return runProcess<InvoiceOutput>(PROCESSES.save, { ...input }, { idempotencyKey })
}

export function postInvoice(invoiceId: string, idempotencyKey?: string): Promise<InvoiceOutput> {
  return runProcess<InvoiceOutput>(PROCESSES.post, { invoiceId }, { idempotencyKey })
}

export function recordReceipt(input: ReceiptInput, idempotencyKey?: string): Promise<ReceiptOutput> {
  return runProcess<ReceiptOutput>(PROCESSES.record, { ...input }, { idempotencyKey })
}

export function loadInvoiceRegister(params: { from: string; to: string; status?: string | null;
  kind?: string | null; customerCode?: string | null }) {
  return runQuery<InvoiceRegisterRow>(QUERIES.invoiceRegister, {
    params: { from: params.from, to: params.to, status: params.status ?? null, kind: params.kind ?? null,
      customerCode: params.customerCode || null },
    limit: PAGE,
    count: true,
  })
}

export function loadReceiptRegister(params: { from: string; to: string; status?: string | null;
  customerCode?: string | null }) {
  return runQuery<ReceiptRegisterRow>(QUERIES.receiptRegister, {
    params: { from: params.from, to: params.to, status: params.status ?? null,
      customerCode: params.customerCode || null },
    limit: PAGE,
    count: true,
  })
}

export async function loadSuggestions(params: { customerCode: string; onDate: string; amount?: string | null;
  reference?: string | null }): Promise<Suggestion[]> {
  const page = await runQuery<Suggestion>(QUERIES.suggestions, {
    params: { customerCode: params.customerCode, onDate: params.onDate, amount: params.amount || null,
      reference: params.reference || null },
    limit: PAGE,
  })
  return page.items
}
