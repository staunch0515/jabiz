import { describe, expect, it } from 'vitest'
import extension from './index'

describe('the finance extension', () => {
  it('offers the imports in the finance menu, each behind the permission its import needs', () => {
    const imports = extension.menu?.find((item) => item.key === 'imports')
    const items = Object.fromEntries((imports?.children ?? []).map((item) => [item.key, item]))
    expect(items.journalImport).toMatchObject({ path: '/imports/run?id=finance.journals', permission: 'fin.journal.prepare' })
    expect(items.payrollImport).toMatchObject({ path: '/imports/run?id=finance.payroll', permission: 'fin.payroll.import' })
    expect(items.openingImport).toMatchObject({ path: '/imports/run?id=finance.opening_balances', permission: 'fin.migration' })
    expect(items.migrationReport?.permission).toBe('fin.journal.read')
    expect(items.importRuns).toMatchObject({ path: '/imports/runs', permission: 'fin.import' })
  })

  it('offers the receivables pages and reports, each behind its permission', () => {
    const ar = extension.menu?.find((item) => item.key === 'ar')
    const items = Object.fromEntries((ar?.children ?? []).map((item) => [item.key, item]))
    expect(items.invoices).toMatchObject({ path: '/receivables/invoices', permission: 'fin.ar.read' })
    expect(items.newInvoice).toMatchObject({ path: '/receivables/invoices/new', permission: 'fin.invoice.prepare' })
    expect(items.receipts).toMatchObject({ path: '/receivables/receipts', permission: 'fin.ar.read' })
    expect(items.newReceipt).toMatchObject({ path: '/receivables/receipts/new', permission: 'fin.receipt.record' })
    expect(items.aging).toMatchObject({ path: '/reports/run?id=finance.ar.aging', permission: 'fin.ar.read' })
    expect(items.salesTax?.path).toBe('/reports/run?id=finance.tax.sales_tax')
    expect(items.companyProfile?.permission).toBe('fin.company.maintain')
    expect(extension.routes?.map((route) => route.path)).toEqual(expect.arrayContaining([
      '/receivables/invoices', '/receivables/invoices/new', '/receivables/invoices/:invoiceId',
      '/receivables/receipts', '/receivables/receipts/new', '/receivables/receipts/:receiptId']))
  })

  it('offers the payables pages, reports and imports, each behind its permission', () => {
    const ap = extension.menu?.find((item) => item.key === 'ap')
    const items = Object.fromEntries((ap?.children ?? []).map((item) => [item.key, item]))
    expect(items.bills).toMatchObject({ path: '/payables/bills', permission: 'fin.ap.read' })
    expect(items.newBill).toMatchObject({ path: '/payables/bills/new', permission: 'fin.bill.prepare' })
    expect(items.paymentRuns).toMatchObject({ path: '/payables/runs', permission: 'fin.ap.read' })
    expect(items.newPaymentRun).toMatchObject({ path: '/payables/runs/new', permission: 'fin.payment.prepare' })
    expect(items.payments).toMatchObject({ path: '/payables/payments', permission: 'fin.ap.read' })
    expect(items.apAging?.path).toBe('/reports/run?id=finance.ap.aging')
    expect(items.vendorStatement?.path).toBe('/reports/run?id=finance.ap.vendor_statement')
    expect(items.form1099?.path).toBe('/reports/run?id=finance.ap.form_1099')
    expect(items.review1099).toMatchObject({ path: '/reports/run?id=finance.ap.form_1099_review',
      permission: 'fin.1099.maintain' })
    const imports = Object.fromEntries((extension.menu?.find((item) => item.key === 'imports')?.children ?? [])
      .map((item) => [item.key, item]))
    expect(imports.vendorImport).toMatchObject({ path: '/imports/run?id=finance.vendors', permission: 'fin.vendor.maintain' })
    expect(imports.openPayablesImport?.permission).toBe('fin.migration')
    expect(extension.routes?.map((route) => route.path)).toEqual(expect.arrayContaining([
      '/payables/bills', '/payables/bills/new', '/payables/bills/:billId', '/payables/runs', '/payables/runs/new',
      '/payables/runs/:runId', '/payables/payments']))
  })

  it('offers the close workspace and the close reports and records, each behind its permission', () => {
    const close = extension.menu?.find((item) => item.key === 'close')
    const items = Object.fromEntries((close?.children ?? []).map((item) => [item.key, item]))
    expect(items.closeWorkspace).toMatchObject({ path: '/close', permission: 'fin.period.read' })
    expect(items.closeOverview?.path).toBe('/reports/run?id=finance.close.overview')
    expect(items.priorPeriodItems?.path).toBe('/reports/run?id=finance.gl.prior_period_items')
    expect(items.closeTemplateSave).toMatchObject({ path: '/processes/FIN_CLOSE_TEMPLATE_SAVE/1',
      permission: 'fin.period.close' })
    expect(extension.routes?.map((route) => route.path)).toContain('/close')
  })
})
