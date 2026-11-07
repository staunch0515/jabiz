import { formatAmount, formatDateTime, runQuery } from '@jabiz/client'
import { Badge, DataTable, ListPage, PageState, type ColumnDef } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { PlusIcon } from 'lucide-react'
import { useMemo, useState } from 'react'
import { AREAS } from '../areas'
import { portalLink } from '../router'
import { useTranslation } from 'react-i18next'
import { PORTAL } from '../messages'

interface OrderRow {
  orderNo: string
  status: string
  orderedTime: string
  totalAmount: string | number
  lineCount: number
}

/** "All" among the status chips. */
const ALL = '*'

/**
 * The customer's own orders (template `commerce.portal_orders`, scoped to the caller by the server), on the
 * platform's list template (phase 17zj): placing an order is the main action beside the title (the floating button
 * on a phone), the toolbar searches the order numbers and picks a status. A `DataTable` whose columns have card
 * places (17zc): on a phone the orders are cards (the number as the title, the status as a badge, the rest as label
 * and value); the customer can switch.
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
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState(ALL)
  const items = orders.data?.items ?? []
  const statuses = [...new Set(items.map((order) => order.status))].sort()
  const shown = items.filter((order) => (status === ALL || order.status === status)
    && order.orderNo.toLowerCase().includes(search.trim().toLowerCase()))
  return (
    <ListPage
      title={t('orders.title')}
      icon={AREAS.orders.icon}
      primaryAction={{ label: t('nav.order'), icon: PlusIcon, renderLink: (props) => portalLink(AREAS.order.path, props) }}
      search={items.length > 0 ? { value: search, onChange: setSearch, label: t('orders.search') } : undefined}
      chips={statuses.length > 1 ? {
        label: t('orders.status'),
        value: status,
        onChange: setStatus,
        options: [{ value: ALL, label: t('orders.all') }, ...statuses.map((s) => ({ value: s, label: s }))],
      } : undefined}
    >
      {orders.isPending && <PageState kind="loading" />}
      {orders.isError && <PageState kind="error" description={t('failed')} data-testid="orders-error" />}
      {orders.data && orders.data.items.length === 0 && (
        <p className="text-muted-foreground" data-testid="orders-empty">
          {t('orders.empty')}
        </p>
      )}
      {orders.data && orders.data.items.length > 0 && (
        <div data-testid="orders-table">
          <DataTable label={t('orders.title')} columns={columns} data={shown}
            getRowId={(order) => order.orderNo} storageKey="portal.orders"
            rowProps={() => ({ 'data-testid': 'order-row' })} />
        </div>
      )}
    </ListPage>
  )
}
