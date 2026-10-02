/** The payables pages' paths (outside the platform's own, decision D22). */
export const BILLS_PATH = '/payables/bills'
export const NEW_BILL_PATH = '/payables/bills/new'
export const billPath = (billId: string) => `/payables/bills/${encodeURIComponent(billId)}`
/** A new vendor credit rather than a bill. */
export const NEW_CREDIT_PATH = `${NEW_BILL_PATH}?kind=CREDIT`
export const RUNS_PATH = '/payables/runs'
export const NEW_RUN_PATH = '/payables/runs/new'
export const runPath = (runId: string) => `/payables/runs/${encodeURIComponent(runId)}`
export const PAYMENTS_PATH = '/payables/payments'
