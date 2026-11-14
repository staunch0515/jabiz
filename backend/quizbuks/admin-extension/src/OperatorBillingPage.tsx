import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Badge,
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  PageHeader,
  PageState,
  useAuth,
  UserName,
  type ColumnDef,
} from '@jabiz/admin'
import { BillingDialog } from './BillingDialog'
import { EmptyState, LoadFailed } from './components'
import { FeeCreditNotes } from './FeeCreditNotes'
import { useLabels, usePaged, usePoints, when } from './hooks'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { OPERATOR_NOTE_CHECKS, readOperatorBilling, readOperatorReconNotes, type OperatorBilling } from './operator-api'

/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { company: 'company', unpaid: 'unpaidTotal' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const KEY = ['qb-ext', 'operator-billing'] as const

/**
 * F-M14b: what the latest daily reconciliation noted about the operator's own sponsors (`qb.operator.recon-notes`):
 * publications charged its floor price, and publications of another's video that bought no exposures. Notes only:
 * nothing is wrong, no task is made, and the operator decides whether to look.
 */
function ReconNotes() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const query = useQuery({ queryKey: [...KEY, 'recon-notes'], queryFn: readOperatorReconNotes })
  let body
  if (query.isError) {
    body = <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
  } else if (query.isPending) {
    body = <PageState kind="loading" />
  } else {
    const { asOf, notes } = query.data
    body = (
      <>
        <p className="text-muted-foreground text-sm" data-testid="operator-recon-notes-as-of">
          {asOf ? t('operator.billingPage.notes.asOf', { when: when(asOf) }) : t('operator.billingPage.notes.noRun')}
        </p>
        {asOf && notes.length === 0 && (
          <p className="text-muted-foreground text-sm" data-testid="operator-recon-notes-none">
            {t('operator.billingPage.notes.none')}</p>
        )}
        {OPERATOR_NOTE_CHECKS.map((check) => {
          const rows = notes.filter((note) => note.checkCode === check)
          return rows.length > 0 && (
            <div key={check} className="flex flex-col gap-2" data-testid="operator-recon-note-group" data-check={check}>
              <h3 className="font-semibold">{t(`operator.billingPage.notes.check.${check}`)}</h3>
              <p className="text-muted-foreground text-sm">{t(`operator.billingPage.notes.help.${check}`)}</p>
              <ul className="flex flex-col gap-1">
                {rows.map((note) => (
                  <li key={note.sponsorId} className="flex flex-wrap gap-x-3" data-testid="operator-recon-note"
                    data-id={note.sponsorId}>
                    <Link to={PATH.sponsor(note.sponsorId)} className={linkClass}>{note.company || note.contactName}</Link>
                    <span className="tabular-nums">
                      {t('operator.billingPage.notes.publications', { count: note.publications })}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )
        })}
      </>
    )
  }
  return (
    <section aria-labelledby="operator-recon-notes-title" className="flex flex-col gap-3" data-testid="operator-recon-notes">
      <h2 id="operator-recon-notes-title" className="text-lg font-semibold">{t('operator.billingPage.notes.title')}</h2>
      <p className="text-muted-foreground text-sm">{t('operator.billingPage.notes.description')}</p>
      {body}
    </section>
  )
}

/**
 * How the operator's own sponsors pay the platform's fees (Q87b, `qb.operator.sponsor-billing`): the billing mode in
 * effect (the sponsor's own, or the platform's default), the credit, what is unpaid or not invoiced yet, and who set
 * it why. The operator changes it with a reason (`QB_OPERATOR_BILLING_SET`); the server refuses a sponsor that is
 * not its own.
 */
export default function OperatorBillingPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const billingMode = useLabels(DICTIONARY.billingMode)
  const points = usePoints()
  const { can } = useAuth()
  const biller = can(PERMISSION.feeBilling)
  const [billing, setBilling] = useState<OperatorBilling | null>(null)
  const [search, setSearch] = useSearchParams()
  const q = search.get('q') ?? ''
  const [typed, setTyped] = useState(q)
  const { query, table, sortable } = usePaged([...KEY, q],
    (offset, limit, sorts) => readOperatorBilling(q, offset, limit, sorts), true, SORTS)
  const columns = useMemo<ColumnDef<OperatorBilling, unknown>[]>(() => [
    {
      id: 'company',
      header: t('operator.billingPage.sponsor'),
      cell: ({ row }) => (
        <Link to={PATH.sponsor(row.original.userId)} className={linkClass} data-testid="billing-sponsor-link">
          {row.original.company || row.original.contactName}
        </Link>
      ),
    },
    {
      id: 'mode',
      header: t('operator.billingPage.mode'),
      cell: ({ row }) => {
        const s = row.original
        return (
          <span className="flex flex-wrap items-center gap-2" data-testid="billing-row-mode" data-mode={s.billingMode}>
            <span>{billingMode(s.billingMode)}</span>
            {!s.own && <Badge variant="outline">{t('operator.billingPage.default')}</Badge>}
            {s.billingMode === 'POSTPAID' && (
              <span className="text-muted-foreground text-sm">
                {t('operator.sponsors.creditLimit', { amount: points(s.creditLimit) })}
              </span>
            )}
          </span>
        )
      },
    },
    {
      id: 'unpaid',
      header: t('operator.billingPage.unpaid'),
      meta: right,
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center justify-end gap-2" data-testid="billing-row-unpaid">
          {points(row.original.unpaidTotal)}
          {row.original.overdueCount > 0 && (
            <Badge variant="destructive">{t('operator.billingPage.overdue', { count: row.original.overdueCount })}</Badge>
          )}
        </span>
      ),
    },
    { id: 'uninvoiced', header: t('operator.billingPage.uninvoiced'), meta: right,
      cell: ({ row }) => <span data-testid="billing-row-uninvoiced">{points(row.original.uninvoicedTotal)}</span> },
    {
      id: 'reason',
      header: t('operator.billingPage.reason'),
      cell: ({ row }) => {
        const s = row.original
        if (!s.own) return null
        return (
          <span className="flex flex-col gap-1">
            <span>{s.billingReason}</span>
            {s.billingSetBy && (
              <span className="text-muted-foreground text-sm">
                {t('operator.billingPage.setBy', { when: when(s.billingSetAt) })} <UserName id={s.billingSetBy} />
              </span>
            )}
          </span>
        )
      },
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('operator.billingPage.set')}</span>,
      cell: ({ row }) => biller && (
        <Button size="sm" variant="outline" data-testid="billing-row-set" onClick={() => setBilling(row.original)}>
          {t('operator.billingPage.set')}<span className="sr-only">: {row.original.company}</span>
        </Button>
      ),
    },
  ], [t, billingMode, points, biller])
  return (
    <div className="flex flex-col gap-4" data-testid="operator-billing-page">
      <PageHeader title={t('operator.billingPage.title')} description={t('operator.billingPage.description')}
        className="pb-0" />
      <form className="flex flex-wrap items-end gap-4" role="search" onSubmit={(event) => {
        event.preventDefault()
        const next = new URLSearchParams(search)
        if (typed.trim()) next.set('q', typed.trim())
        else next.delete('q')
        setSearch(next, { replace: true })
      }}>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-q`}>{t('operator.billingPage.search')}</Label>
          <Input id={`${id}-q`} type="search" value={typed} maxLength={100} className="w-72" data-testid="billing-q"
            onChange={(event) => setTyped(event.target.value)} />
        </div>
        <Button type="submit" variant="outline">{t('operator.billingPage.find')}</Button>
      </form>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('operator.billingPage.title')} columns={sortable(columns)} {...table}
            getRowId={(row) => row.userId} empty={<EmptyState art="people" title={t('operator.billingPage.none')} />}
            rowProps={(row) => ({ 'data-testid': 'billing-row', 'data-id': row.userId })} />
        )}
      <ReconNotes />
      {/* F-M7: the credit notes on its Sponsors' fees, read only (what they take back is deducted from its share). */}
      <section aria-labelledby="operator-credit-notes-title" className="flex flex-col gap-4" data-testid="operator-credit-notes">
        <h2 id="operator-credit-notes-title" className="text-lg font-semibold">{t('fee.creditNotes.title')}</h2>
        <FeeCreditNotes operator />
      </section>
      {billing && (
        <BillingDialog sponsor={billing} onClose={() => setBilling(null)}
          onSaved={() => void queryClient.invalidateQueries({ queryKey: KEY })} />
      )}
    </div>
  )
}
