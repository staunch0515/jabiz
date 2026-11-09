import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, DataTable, EXTENSION_NAMESPACE, UserName, type ColumnDef } from '@jabiz/admin'
import { EmptyState, LoadFailed } from './components'
import { FEES_KEY, GRANT_GROUPS, readFeeCreditGrants, readFeeCreditSummary, type FeeCreditGrant, type FeeCreditSum,
  type GrantGroup } from './fee-api'
import { money, useLabels, usePaged, when } from './hooks'
import { DICTIONARY } from './ids'

const right = { className: 'text-right tabular-nums' }
const GRANT_SORTS = { grantedAt: 'grantedAt', expiresAt: 'expiresAt', amount: 'amount' } as const

/**
 * The platform fee Kudos granted (F-M2), for headquarters, read only: summed by the operator the Sponsor had when it
 * was granted, by Sponsor or by source, and every lot with what was spent, what is left and what expired unused. The
 * grants themselves are made elsewhere (an operator's lead confirms a Sponsor; finance asks for a grant by hand).
 */
export function FeeCreditGrants() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const source = useLabels(DICTIONARY.feeCreditSource)
  const [group, setGroup] = useState<GrantGroup>('OPERATOR')
  const summary = usePaged([...FEES_KEY, 'credit-summary', group],
    (offset, limit) => readFeeCreditSummary(group, offset, limit))
  const grants = usePaged([...FEES_KEY, 'credit-grants'],
    (offset, limit, sorts) => readFeeCreditGrants(offset, limit, sorts), true, GRANT_SORTS)
  const sumColumns = useMemo<ColumnDef<FeeCreditSum, unknown>[]>(() => [
    { id: 'label', header: t(`fee.grants.by.${group}`), cell: ({ row }) => (group === 'SOURCE'
      ? source(row.original.groupKey)
      : row.original.groupKey === '-' ? t('fee.grants.noOperator')
        : row.original.label ?? (group === 'SPONSOR' ? <UserName id={row.original.groupKey} /> : row.original.groupKey)) },
    { id: 'lots', header: t('fee.grants.lots'), meta: right, cell: ({ row }) => row.original.lots },
    { id: 'granted', header: t('fee.grants.granted'), meta: right, cell: ({ row }) => money(row.original.granted) },
    { id: 'spent', header: t('fee.grants.spent'), meta: right, cell: ({ row }) => money(row.original.spent) },
    { id: 'remaining', header: t('fee.grants.remaining'), meta: right, cell: ({ row }) => money(row.original.remaining) },
    { id: 'voided', header: t('fee.grants.voided'), meta: right, cell: ({ row }) => money(row.original.voided) },
  ], [t, group, source])
  const grantColumns = useMemo<ColumnDef<FeeCreditGrant, unknown>[]>(() => [
    { id: 'grantedAt', header: t('fee.grants.grantedAt'), cell: ({ row }) => when(row.original.grantedAt) },
    { id: 'sponsor', header: t('fee.sponsor'),
      cell: ({ row }) => row.original.company ?? <UserName id={row.original.sponsorId} /> },
    { id: 'operator', header: t('fee.grants.operator'),
      cell: ({ row }) => row.original.operatorName ?? t('fee.grants.noOperator') },
    { id: 'source', header: t('fee.grants.source'), cell: ({ row }) => source(row.original.source) },
    { id: 'amount', header: t('fee.grants.granted'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'spent', header: t('fee.grants.spent'), meta: right, cell: ({ row }) => money(row.original.spent) },
    { id: 'remaining', header: t('fee.grants.remaining'), meta: right, cell: ({ row }) => money(row.original.remaining) },
    { id: 'voided', header: t('fee.grants.voided'), meta: right, cell: ({ row }) => money(row.original.voidedAmount) },
    { id: 'expiresAt', header: t('fee.grants.expiresAt'), cell: ({ row }) => when(row.original.expiresAt) },
  ], [t, source])
  return (
    <div className="flex flex-col gap-4" data-testid="fee-credit-grants">
      <p className="text-muted-foreground text-sm">{t('fee.grants.hint')}</p>
      <div className="flex flex-wrap gap-2" role="group" aria-label={t('fee.grants.groupBy')}>
        {GRANT_GROUPS.map((value) => (
          <Button key={value} size="sm" variant={group === value ? 'default' : 'outline'} aria-pressed={group === value}
            onClick={() => setGroup(value)} data-testid={`fee-grants-by-${value}`}>
            {t(`fee.grants.by.${value}`)}
          </Button>
        ))}
      </div>
      {summary.query.isError
        ? <LoadFailed error={summary.query.error} onRetry={() => void summary.query.refetch()} />
        : (
          <DataTable label={t('fee.grants.summary')} columns={sumColumns} {...summary.table}
            getRowId={(row) => row.groupKey} empty={<EmptyState art="money" title={t('fee.grants.none')} />}
            rowProps={(row) => ({ 'data-testid': 'fee-grant-sum-row', 'data-key': row.groupKey })} />
        )}
      <h3 className="font-semibold">{t('fee.grants.all')}</h3>
      {grants.query.isError
        ? <LoadFailed error={grants.query.error} onRetry={() => void grants.query.refetch()} />
        : (
          <DataTable label={t('fee.grants.all')} columns={grants.sortable(grantColumns)} {...grants.table}
            getRowId={(row) => row.grantId} empty={<EmptyState art="money" title={t('fee.grants.none')} />}
            rowProps={(row) => ({ 'data-testid': 'fee-grant-row', 'data-source': row.source })} />
        )}
    </div>
  )
}
