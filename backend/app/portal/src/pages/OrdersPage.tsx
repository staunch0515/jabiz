import { formatAmount, formatDateTime, runQuery } from '@jabiz/client'
import { PageState, Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { PORTAL } from '../messages'

interface OrderRow {
  orderNo: string
  status: string
  orderedTime: string
  totalAmount: string | number
  lineCount: number
}

/** The customer's own orders (template `commerce.portal_orders`, scoped to the caller by the server). */
export function OrdersPage() {
  const { t } = useTranslation(PORTAL)
  const orders = useQuery({
    queryKey: ['portal', 'orders'],
    queryFn: () => runQuery<OrderRow>('commerce.portal_orders', { limit: 50 }),
  })
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
        <Table data-testid="orders-table">
          <TableHeader>
            <TableRow>
              <TableHead>{t('orders.orderNo')}</TableHead>
              <TableHead>{t('orders.status')}</TableHead>
              <TableHead>{t('orders.orderedTime')}</TableHead>
              <TableHead className="text-right">{t('orders.lineCount')}</TableHead>
              <TableHead className="text-right">{t('orders.totalAmount')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {orders.data.items.map((order) => (
              <TableRow key={order.orderNo} data-testid="order-row">
                <TableCell className="font-medium">{order.orderNo}</TableCell>
                <TableCell>{order.status}</TableCell>
                <TableCell>{formatDateTime(order.orderedTime)}</TableCell>
                <TableCell className="text-right">{order.lineCount}</TableCell>
                <TableCell className="text-right">
                  {formatAmount(order.totalAmount, { currency: 'JPY', scale: 0 })}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </section>
  )
}
