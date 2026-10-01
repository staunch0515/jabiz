/** The receivables pages' paths (outside the platform's own, decision D22). */
export const INVOICES_PATH = '/receivables/invoices'
export const NEW_INVOICE_PATH = '/receivables/invoices/new'
export const invoicePath = (invoiceId: string) => `/receivables/invoices/${encodeURIComponent(invoiceId)}`
/** A new credit memo for an invoice: its customer and lines to start from. */
export const newCreditMemoPath = (invoiceId: string) => `${NEW_INVOICE_PATH}?credits=${encodeURIComponent(invoiceId)}`
export const RECEIPTS_PATH = '/receivables/receipts'
export const NEW_RECEIPT_PATH = '/receivables/receipts/new'
export const receiptPath = (receiptId: string) => `/receivables/receipts/${encodeURIComponent(receiptId)}`
