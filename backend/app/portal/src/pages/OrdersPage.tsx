import { formatAmount, formatDateTime, runQuery } from '@jabiz/client'
import { Badge, DataTable, PageState, type ColumnDef } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { PORTAL } from '../messages'

interface OrderRow {
  orderNo: string
  status: string
  orderedTime: string
  totalAmount: string | number
  lineCount: number
}

/**
 * The customer's own orders (template `commerce.portal_orders`, scoped to the caller by the server). A `DataTable`
 * whose columns have card places (17zc): on a phone the orders are cards (the number as the title, the status as a
 * badge, the rest as label and value); the customer can switch.
 */
export function OrdersPage() {
  const { t } = useTranslation(PORTAL)
  const orders = useQuery({
    queryKey: ['portal', 'orders'],
    queryFn: () => runQuery<OrderRow>('commerce.portal_orders', { limit: 50 }),
  })
  const columns = useMemo<ColumnDef<OrderRow, unknown>[]>(() => [
    { accessorKey: 'orderNo', header: t('orders.orderNo'), meta: { card: 'title', className: 'font-medium' },
      enableSorting: false },
    { accessorKey: 'status', header: t('orders.status'), meta: { card: 'badge' }, enableSorting: false,
      cell: ({ row }) => <Badge variant="outline">{row.original.status}</Badge> },
    { accessorKey: 'orderedTime', header: t('orders.orderedTime'), enableSorting: false,
      cell: ({ row }) => formatDateTime(row.original.orderedTime) },
    { accessorKey: 'lineCount', header: t('orders.lineCount'), meta: { className: 'text-right' }, enableSorting: false },
    { accessorKey: 'totalAmount', header: t('orders.totalAmount'), meta: { className: 'text-right' }, enableSorting: false,
      cell: ({ row }) => formatAmount(row.original.totalAmount, { currency: 'JPY', scale: 0 }) },
  ], [t])
  return (
    <section className="flex flex-col gap-4" aria-labelledby="orders-title">
      <h1 id="orders-title" className="text-xl font-semibold">
        {t('orders.title')}
      </h1>
      {orders.isPending && <PageState kind="loading" />}
      {orders.isError && <PageState kind="error" description={t('failed')} data-testid="orders-error" />}
      {orders.data && orders.data.items.length === 0 && (
        <p className="text-muted-foreground" data-testid="orders-empty">
          {t('orders.empty')}
        </p>
      )}
      {orders.data && orders.data.items.length > 0 && (
        <div data-testid="orders-table">
          <DataTable label={t('orders.title')} columns={columns} data={orders.data.items}
            getRowId={(order) => order.orderNo} storageKey="portal.orders"
            rowProps={() => ({ 'data-testid': 'order-row' })} />
        </div>
      )}
    </section>
  )
}
