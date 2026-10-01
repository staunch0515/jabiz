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
})
