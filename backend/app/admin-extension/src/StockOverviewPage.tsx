import { useQuery, useQueryClient } from '@tanstack/react-query'
import { App, Button, Card, Form, Input, InputNumber, Space, Table, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { ApiError, EXTENSION_NAMESPACE, paths, runProcess, runQuery, useAuth } from '@jabiz/admin'

export interface StockRow {
  warehouseCode: string
  sku: string
  productName: string
  onHand: string | number
  reserved: string | number
  available: string | number
}

interface ReceiveOutput {
  warehouseCode: string
  sku: string
  onHand: string | number
}

const QUERY = 'commerce.stock_availability'
/** Every receipt, written once (docs/design/04-temporal-append-only.md section 5.4), on its generated list page. */
const RECEIPTS = 'urn:jabiz:dataset:default:StockReceipt'

/**
 * Stock per warehouse and product, and a receipt of goods. Reading needs commerce.stock.read (the template's
 * permission), receiving commerce.stock.receive (the process's); the form is offered only to those who may receive,
 * and the server decides either way.
 */
export default function StockOverviewPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { message } = App.useApp()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const [warehouse, setWarehouse] = useState<string>()
  const [receiving, setReceiving] = useState(false)
  const [form] = Form.useForm<{ warehouseCode: string; sku: string; quantity: number }>()

  const stock = useQuery({
    queryKey: ['ext', QUERY, warehouse ?? null],
    queryFn: () => runQuery<StockRow>(QUERY, { params: { warehouseCode: warehouse ?? null }, limit: 100 }),
  })

  const receive = async (values: { warehouseCode: string; sku: string; quantity: number }) => {
    setReceiving(true)
    try {
      const out = await runProcess<ReceiveOutput>('STOCK_RECEIVE', values)
      message.success(t('stock.received', { sku: out.sku, warehouse: out.warehouseCode, onHand: out.onHand }))
      form.resetFields(['quantity'])
      await queryClient.invalidateQueries({ queryKey: ['ext', QUERY] })
    } catch (e) {
      message.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setReceiving(false)
    }
  }

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Typography.Title level={3} data-testid="page-title">
        {t('stock.title')}
      </Typography.Title>
      <Link to={paths.dataset(RECEIPTS)} data-testid="receipts-link">
        {t('stock.receipts')}
      </Link>
      {can('commerce.stock.receive') && (
        <Card size="small" title={t('stock.receive')}>
          <Form form={form} layout="inline" onFinish={receive} data-testid="receive-form">
            <Form.Item name="warehouseCode" label={t('stock.warehouse')} rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="sku" label={t('stock.sku')} rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="quantity" label={t('stock.quantity')} rules={[{ required: true }]}>
              <InputNumber min={1} precision={0} />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={receiving}>
              {t('stock.receive')}
            </Button>
          </Form>
        </Card>
      )}
      <Input.Search
        allowClear
        placeholder={t('stock.filter')}
        aria-label={t('stock.filter')}
        onSearch={(value) => setWarehouse(value.trim() || undefined)}
        style={{ maxWidth: 320 }}
      />
      <Table<StockRow>
        data-testid="stock-table"
        rowKey={(row) => `${row.warehouseCode}/${row.sku}`}
        loading={stock.isLoading}
        dataSource={stock.data?.items ?? []}
        locale={{ emptyText: stock.error instanceof ApiError ? stock.error.display : t('stock.empty') }}
        pagination={false}
        columns={[
          { title: t('stock.warehouse'), dataIndex: 'warehouseCode' },
          { title: t('stock.sku'), dataIndex: 'sku' },
          { title: t('stock.product'), dataIndex: 'productName' },
          { title: t('stock.onHand'), dataIndex: 'onHand', align: 'right' },
          { title: t('stock.reserved'), dataIndex: 'reserved', align: 'right' },
          { title: t('stock.available'), dataIndex: 'available', align: 'right' },
        ]}
      />
    </Space>
  )
}
