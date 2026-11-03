import { formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  DataTable,
  DescriptionList,
  type ColumnDef,
  type PaginationState,
  type SortingState,
} from '@jabiz/ui'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { ArrowDownToLine, ArrowUpFromLine } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { searchKudosExchanges, WALLET_QUERY, type ExchangeRow, type SponsorWallet, type TopUpSettings } from './api'
import { ExchangeDialog, exchangeDone } from './BuyKudos'
import './messages'

const PAGE_SIZE = 10

/**
 * The sponsor's cash account (Q87): money paid in that is not Kudos yet (`qb.sponsor.wallet`), the fees unpaid, how
 * the platform bills, and the exchange with the Kudos wallet (`qb.sponsor.kudos-exchanges`). Buying and selling
 * follow the administrators' switches (`qb.sponsor.topup-settings`); the server decides each call.
 */
export function CashAccount({ wallet, settings }: { wallet: SponsorWallet; settings: TopUpSettings | undefined }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const [exchanging, setExchanging] = useState<'BUY' | 'SELL' | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [sorting, setSorting] = useState<SortingState>([{ id: 'exchangedAt', desc: true }])
  const exchanges = useQuery({
    queryKey: [...WALLET_QUERY, 'exchanges', pagination, sorting],
    queryFn: () => searchKudosExchanges({
      offset: pagination.pageIndex * pagination.pageSize,
      limit: pagination.pageSize,
      sorts: sorting.map((s) => ({ field: s.id, asc: !s.desc })),
    }),
    placeholderData: keepPreviousData,
  })
  const columns = useMemo<ColumnDef<ExchangeRow, unknown>[]>(() => [
    { id: 'exchangedAt', header: t('wallet.cash.when'), meta: { card: 'subtitle' },
      cell: ({ row }) => formatDateTime(row.original.exchangedAt) },
    { id: 'direction', header: t('wallet.cash.direction'), enableSorting: false, meta: { card: 'badge' },
      cell: ({ row }) => (
        <Badge variant={row.original.direction === 'BUY' ? 'success' : 'secondary'} data-testid="exchange-row-direction"
          data-direction={row.original.direction}>
          {t(`wallet.cash.directions.${row.original.direction}`)}
        </Badge>
      ) },
    { id: 'amount', header: t('wallet.amount'), meta: { className: 'text-right', card: 'title' },
      cell: ({ row }) => <span data-testid="exchange-row-amount">{formatKudos(row.original.amount)}</span> },
  ], [t])
  const cashAvailable = Math.max(0, wallet.cashBalance - wallet.cashHeld)
  const canBuy = settings?.buyEnabled === true && cashAvailable > 0
  return (
    <section aria-labelledby="wallet-cash" className="flex flex-col gap-3" data-testid="wallet-cash">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="wallet-cash" className="text-lg font-semibold">{t('wallet.cash.title')}</h2>
        <span className="flex flex-wrap gap-2">
          {settings?.buyEnabled && (
            <Button size="sm" disabled={!canBuy} data-testid="kudos-buy" onClick={() => setExchanging('BUY')}>
              <ArrowDownToLine aria-hidden />
              {t('wallet.cash.buy')}
            </Button>
          )}
          {settings?.sellEnabled && (
            <Button size="sm" variant="outline" disabled={wallet.balance <= 0} data-testid="kudos-sell"
              onClick={() => setExchanging('SELL')}>
              <ArrowUpFromLine aria-hidden />
              {t('wallet.cash.sell')}
            </Button>
          )}
        </span>
      </div>
      <p className="text-muted-foreground text-sm">{t('wallet.cash.intro')}</p>
      {wallet.overdueCount > 0 ? (
        <Alert variant="destructive" data-testid="wallet-overdue">
          <AlertDescription>{t('wallet.cash.overdueAlert', { count: wallet.overdueCount })}</AlertDescription>
        </Alert>
      ) : wallet.unpaidCount > 0 && (
        <Alert variant="warning" data-testid="wallet-unpaid">
          <AlertDescription>{t('wallet.cash.unpaidAlert', { amount: formatKudos(wallet.unpaidTotal) })}</AlertDescription>
        </Alert>
      )}
      {settings && !settings.buyEnabled && (
        <p className="text-muted-foreground text-sm" data-testid="kudos-buy-off">{t('wallet.cash.buyOff')}</p>
      )}
      {settings?.buyEnabled && cashAvailable <= 0 && (
        <p className="text-muted-foreground text-sm" data-testid="kudos-buy-empty">{t('wallet.cash.buyEmpty')}</p>
      )}
      {done && (
        <Alert data-testid="exchange-done"><AlertDescription>{done}</AlertDescription></Alert>
      )}
      <DescriptionList columns={3} data-testid="wallet-cash-figures" items={[
        { key: 'cash', label: t('wallet.cash.balance'),
          value: <span data-testid="wallet-cash-balance">{formatKudos(wallet.cashBalance)}</span> },
        ...(wallet.cashHeld > 0 ? [{ key: 'held', label: t('wallet.cash.held'), value: formatKudos(wallet.cashHeld) }] : []),
        { key: 'unpaid', label: t('wallet.cash.unpaid'),
          value: wallet.unpaidCount > 0
            ? t('wallet.cash.unpaidValue', { amount: formatKudos(wallet.unpaidTotal), count: wallet.unpaidCount })
            : t('wallet.cash.none') },
        { key: 'billing', label: t('wallet.cash.billing'),
          value: <span data-testid="wallet-billing" data-mode={wallet.billingMode}>
            {t(`wallet.cash.billingModes.${wallet.billingMode}`)}</span> },
        ...(wallet.billingMode === 'POSTPAID'
          ? [{ key: 'credit', label: t('wallet.cash.creditLimit'), value: formatKudos(wallet.creditLimit) }] : []),
      ]} />
      <h3 className="text-base font-medium">{t('wallet.cash.exchanges')}</h3>
      {exchanges.isError
        ? <p className="text-destructive text-sm" role="alert">{tQb('failed')}</p>
        : (
          <DataTable label={t('wallet.cash.exchangesTable')} columns={columns} data={exchanges.data?.items ?? []}
            storageKey="qb.sponsor.kudos-exchanges" getRowId={(row) => row.exchangeId} loading={exchanges.isPending}
            stale={exchanges.isPlaceholderData} empty={t('wallet.cash.noExchanges')} pagination={pagination}
            onPaginationChange={setPagination} rowCount={exchanges.data?.total} sorting={sorting}
            onSortingChange={(next) => {
              setSorting(next)
              setPagination((p) => ({ ...p, pageIndex: 0 }))
            }}
            rowProps={(row) => ({ 'data-testid': 'exchange-row', 'data-exchange': row.exchangeId })} />
        )}
      {exchanging && (
        <ExchangeDialog direction={exchanging} open onOpenChange={(open) => { if (!open) setExchanging(null) }}
          onDone={(output) => setDone(exchangeDone(t, output))} />
      )}
    </section>
  )
}
