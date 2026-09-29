import { AppstoreOutlined } from '@ant-design/icons'
import { defineExtension } from '@jabiz/admin'
import { messages } from './messages'
import StockOverviewPage from './StockOverviewPage'

/**
 * The demo application's own admin page (docs/design/12-frontend.md section 9, decision D22): an overview of stock
 * with a receipt form, built from the SQL template commerce.stock_availability and the process STOCK_RECEIVE. The
 * generated pages still maintain products and warehouses; this page is a workflow the metadata cannot express.
 */
export default defineExtension({
  routes: [{ path: '/commerce/stock', element: <StockOverviewPage /> }],
  menu: [
    {
      key: 'stock',
      label: 'menu.stock',
      path: '/commerce/stock',
      icon: <AppstoreOutlined />,
      permission: 'commerce.stock.read',
    },
  ],
  messages,
})
