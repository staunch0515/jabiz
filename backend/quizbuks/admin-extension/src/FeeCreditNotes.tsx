import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { DataTable, EXTENSION_NAMESPACE, UserName, type ColumnDef } from '@jabiz/admin'
import { EmptyState, LoadFailed } from './components'
import { CREDIT_NOTE_REASONS, FEES_KEY, readFeeCreditNotes, type FeeCreditNote } from './fee-api'
import { money, usePaged, usePoints, when } from './hooks'
import { PATH } from './ids'
import { useMoreFilters } from './moreFilters'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
/** The columns sorted on the server, by the templates' `list.sorts` field. */
const SORTS = { issuedAt: 'issuedAt', total: 'total' } as const

/**
 * F-M7: the credit notes (`qb.admin.fee-credit-notes`, an operator's own Sponsors' `qb.operator.fee-credit-notes`),
 * newest first, read only: each with what it took back, what it gave back as cash and as credit, and what is left
 * of that credit. Finance sees who issued a note by hand and the Sponsors' ids; an operator sees yen as points and
 * never who issued it (the template does not give it).
 */
export function FeeCreditNotes({ operator = false }: { operator?: boolean }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const points = usePoints()
  const amount = operator ? points : money
  const more = useMoreFilters([
    { kind: 'eq', name: 'sponsorId', label: t('fee.sponsor') },
    ...(operator ? [] : [{ kind: 'eq', name: 'operatorId', label: t('fee.creditNotes.operator') } as const]),
    { kind: 'eq', name: 'chargeId', label: t('fee.creditNotes.charge') },
    { kind: 'eq', name: 'invoiceNo', label: t('fee.invoiceNo'), upper: true },
    { kind: 'choice', name: 'reason', label: t('fee.reason'),
      options: CREDIT_NOTE_REASONS.map((value) => ({ value, label: t(`fee.creditNotes.reasons.${value}`) })) },
    { kind: 'time', name: 'issuedAt', label: t('fee.issuedAt') },
  ], 'fee-credit-note-filters')
  const { query, table, sortable } = usePaged([...FEES_KEY, 'credit-notes', operator, more.filters],
    (offset, limit, sorts) => readFeeCreditNotes(operator, offset, limit, sorts, more.filters), true, SORTS)
  const columns = useMemo<ColumnDef<FeeCreditNote, unknown>[]>(() => [
    { id: 'issuedAt', header: t('fee.issuedAt'), cell: ({ row }) => when(row.original.issuedAt) },
    { id: 'creditNoteNo', header: t('fee.creditNotes.number'),
      cell: ({ row }) => <code className="font-mono" data-testid="fee-credit-note-no">{row.original.creditNoteNo}</code> },
    { id: 'sponsor', header: t('fee.sponsor'),
      cell: ({ row }) => (operator || !row.original.sponsorId
        ? (row.original.company ?? '')
        : <Link to={PATH.user(row.original.sponsorId)} className={linkClass}>
          {row.original.company ?? <UserName id={row.original.sponsorId} />}
        </Link>) },
    { id: 'invoiceNo', header: t('fee.invoiceNo'), cell: ({ row }) => row.original.invoiceNo ?? '' },
    { id: 'reason', header: t('fee.reason'),
      cell: ({ row }) => (
        <span className="flex flex-col gap-1" data-testid="fee-credit-note-reason" data-reason={row.original.reason}>
          <span>{t(`fee.creditNotes.reasons.${row.original.reason}`, { defaultValue: row.original.reason })}</span>
          {row.original.note && <span className="text-muted-foreground text-sm">{row.original.note}</span>}
        </span>
      ) },
    { id: 'usedDays', header: t('fee.creditNotes.usedDays'), meta: right, cell: ({ row }) => row.original.usedDays ?? '' },
    { id: 'billingMode', header: t('fee.billingMode'),
      cell: ({ row }) => t(`fee.creditNotes.billingModes.${row.original.billingMode}`) },
    { id: 'amount', header: t('fee.amount'), meta: right, cell: ({ row }) => amount(row.original.amount) },
    { id: 'tax', header: t('fee.tax'), meta: right, cell: ({ row }) => amount(row.original.tax) },
    { id: 'total', header: t('fee.total'), meta: right,
      cell: ({ row }) => <span data-testid="fee-credit-note-total">{amount(row.original.total)}</span> },
    { id: 'appliedOwn', header: t('fee.creditNotes.appliedOwn'), meta: right,
      cell: ({ row }) => <span data-testid="fee-credit-note-applied">{amount(row.original.appliedOwn)}</span> },
    { id: 'refundedCash', header: t('fee.creditNotes.refundedCash'), meta: right,
      cell: ({ row }) => <span data-testid="fee-credit-note-cash">{amount(row.original.refundedCash)}</span> },
    { id: 'refundedCredit', header: t('fee.creditNotes.refundedCredit'), meta: right,
      cell: ({ row }) => <span data-testid="fee-credit-note-credit">{amount(row.original.refundedCredit)}</span> },
    { id: 'used', header: t('fee.creditNotes.used'), meta: right, cell: ({ row }) => amount(row.original.used) },
    { id: 'creditLeft', header: t('fee.creditNotes.creditLeft'), meta: right,
      cell: ({ row }) => <span data-testid="fee-credit-note-left">{amount(row.original.creditLeft)}</span> },
    { id: 'voidedCredit', header: t('fee.creditNotes.voidedCredit'), meta: right,
      cell: ({ row }) => amount(row.original.voidedCredit) },
    ...(operator ? [] : [
      { id: 'chargeId', header: t('fee.creditNotes.charge'),
        cell: ({ row }) => (row.original.chargeId ? <code className="font-mono">{row.original.chargeId}</code> : '') },
      { id: 'issuedBy', header: t('fee.creditNotes.issuedBy'),
        cell: ({ row }) => (row.original.issuedBy ? <UserName id={row.original.issuedBy} /> : t('fee.system')) },
    ] as ColumnDef<FeeCreditNote, unknown>[]),
  ], [t, amount, operator])
  return (
    <div className="flex flex-col gap-4" data-testid="fee-credit-notes">
      <p className="text-muted-foreground text-sm">{t(operator ? 'fee.creditNotes.operatorHint' : 'fee.creditNotes.hint')}</p>
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('fee.creditNotes.table')} columns={sortable(columns)} {...table}
            getRowId={(row) => row.creditNoteId} empty={<EmptyState art="money" title={t('fee.creditNotes.none')} />}
            rowProps={(row) => ({ 'data-testid': 'fee-credit-note-row', 'data-id': row.creditNoteId,
              'data-reason': row.reason })} />
        )}
    </div>
  )
}
