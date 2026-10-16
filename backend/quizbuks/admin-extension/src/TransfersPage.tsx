import { useQueryClient } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Badge,
  Button,
  Checkbox,
  ConfirmDialog,
  DataTable,
  EXTENSION_NAMESPACE,
  Label,
  notify,
  PageHeader,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { transferAddressOf, transferFilterOf, failed } from './finance'
import { readFinanceTransfers, releaseHold, type FinanceTransfer, type TransferFilter } from './finance-api'
import { DayRange, StatusFilter } from './finance-components'
import { LoadFailed } from './components'
import { money, useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PATH, PERMISSION, TRANSFER_REVIEW } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }

/** Lets a held taker's transfers go on, after asking; the server checks the permission and the four eyes. */
function Release({ transfer }: { transfer: FinanceTransfer }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const queryClient = useQueryClient()
  return (
    <ConfirmDialog
      title={t('finance.transfers.releaseTitle')}
      description={t('finance.transfers.releaseDescription')}
      confirmLabel={t('finance.transfers.release')}
      onConfirm={async () => {
        try {
          await releaseHold(transfer.userId)
        } catch (error) {
          failed(error, t('common.failed'))
        }
        notify.success(t('finance.transfers.released'))
        await queryClient.invalidateQueries({ queryKey: ['qb-ext', 'finance'] })
      }}
      trigger={<Button variant="outline" size="sm" data-testid="transfer-release">{t('finance.transfers.release')}</Button>}
    />
  )
}

/**
 * Every transfer (P-03), newest first, page by page: filtered by state, period, taker, in doubt or holding the taker
 * (the address carries the filter, so the overview links to a filtered list). The account shows its last four
 * characters only; Stripe's transfer is shown, not linked. A transfer in doubt is marked; a large one waiting for
 * review leads to its review; a held taker can be released by those who review payouts (the server decides).
 */
export default function TransfersPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const status = useLabels(DICTIONARY.transferStatus)
  const [search, setSearch] = useSearchParams()
  const filter = transferFilterOf(search)
  const set = (change: Partial<TransferFilter>) => setSearch(transferAddressOf({ ...filter, ...change }), { replace: true })
  const reviewer = can(PERMISSION.payoutReview)
  const { query, table } = usePaged(['qb-ext', 'finance', 'transfers', filter],
    (offset, limit) => readFinanceTransfers(filter, offset, limit))
  const columns = useMemo<ColumnDef<FinanceTransfer, unknown>[]>(() => [
    { id: 'createdAt', header: t('finance.transfers.createdAt'), cell: ({ row }) => when(row.original.createdAt) },
    {
      id: 'user',
      header: t('finance.transfers.user'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.userId)} className={linkClass} data-testid="transfer-user">
          <UserName id={row.original.userId} />
        </Link>
      ),
    },
    { id: 'amount', header: t('finance.transfers.amount'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'fee', header: t('finance.transfers.fee'), meta: right, cell: ({ row }) => money(row.original.fee) },
    { id: 'net', header: t('finance.transfers.net'), meta: right, cell: ({ row }) => money(row.original.net) },
    {
      id: 'status',
      header: t('finance.transfers.status'),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-1">
          <Badge variant="outline" data-testid="transfer-status">{status(row.original.status)}</Badge>
          {row.original.stale && <Badge variant="warning" data-testid="transfer-stale">{t('finance.transfers.stale')}</Badge>}
          {row.original.held && <Badge variant="secondary" data-testid="transfer-held">{t('finance.transfers.held')}</Badge>}
        </span>
      ),
    },
    {
      id: 'account',
      header: t('finance.transfers.account'),
      cell: ({ row }) => (row.original.accountLast4 ? t('finance.transfers.last4', { last4: row.original.accountLast4 }) : ''),
    },
    {
      id: 'stripe',
      header: t('finance.transfers.stripeTransfer'),
      cell: ({ row }) => (row.original.stripeTransferId
        ? <code className="text-xs break-all">{row.original.stripeTransferId}</code> : ''),
    },
    {
      id: 'reversed',
      header: t('finance.transfers.reversed'),
      meta: right,
      cell: ({ row }) => (Number(row.original.reversedAmount) > 0 ? money(row.original.reversedAmount) : ''),
    },
    {
      id: 'actions',
      header: t('finance.transfers.actions'),
      cell: ({ row }) => (
        <span className="flex flex-wrap gap-2">
          {row.original.status === TRANSFER_REVIEW && row.original.approvalRequestId && (
            <Button variant="outline" size="sm" asChild>
              <Link to={PATH.transferReview(row.original.transferId)} data-testid="transfer-review-link">
                {t('finance.transfers.review')}
              </Link>
            </Button>
          )}
          {row.original.held && reviewer && <Release transfer={row.original} />}
        </span>
      ),
    },
  ], [t, status, reviewer])

  return (
    <div className="flex flex-col gap-4" data-testid="finance-transfers">
      <PageHeader title={t('finance.transfers.title')} description={t('finance.transfers.description')}
        className="pb-0" />
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        <StatusFilter dictionary={DICTIONARY.transferStatus} value={filter.status} testId="transfers-status"
          onChange={(value) => set({ status: value })} />
        <DayRange from={filter.from} to={filter.to} onChange={(from, to) => set({ from, to })} />
        <div className="flex items-center gap-2 pb-2">
          <Checkbox id="transfers-stale" checked={filter.stale} data-testid="transfers-stale"
            onCheckedChange={(checked) => set({ stale: checked === true })} />
          <Label htmlFor="transfers-stale">{t('finance.transfers.onlyStale')}</Label>
        </div>
        <div className="flex items-center gap-2 pb-2">
          <Checkbox id="transfers-held" checked={filter.held} data-testid="transfers-held"
            onCheckedChange={(checked) => set({ held: checked === true })} />
          <Label htmlFor="transfers-held">{t('finance.transfers.onlyHeld')}</Label>
        </div>
        {(filter.userId || filter.transferId) && (
          <Button variant="ghost" size="sm" data-testid="transfers-clear-one"
            onClick={() => set({ userId: null, transferId: null })}>
            {filter.userId ? t('finance.transfers.allUsers') : t('finance.transfers.allTransfers')}
          </Button>
        )}
      </div>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('finance.transfers.table')} columns={columns} {...table}
            getRowId={(row) => row.transferId} empty={t('finance.transfers.none')}
            rowProps={(row) => ({ 'data-testid': 'transfer-row', 'data-id': row.transferId,
              'data-status': row.status, 'data-stale': String(row.stale), 'data-held': String(row.held) })} />
        )}
    </div>
  )
}
