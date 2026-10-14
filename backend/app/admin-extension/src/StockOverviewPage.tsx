import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  ApiError,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  DataTable,
  DecimalInput,
  EXTENSION_NAMESPACE,
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
  Input,
  notify,
  PageHeader,
  paths,
  runProcess,
  runQuery,
  Spinner,
  useAuth,
  useForm,
  type ColumnDef,
} from '@jabiz/admin'

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

/** The receipt form: the quantity is decimal text, as the field holds it. */
interface ReceiveForm {
  warehouseCode: string
  sku: string
  quantity: string
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
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const filterId = useId()
  const [warehouse, setWarehouse] = useState<string>()
  const [filter, setFilter] = useState('')

  const stock = useQuery({
    queryKey: ['ext', QUERY, warehouse ?? null],
    queryFn: () => runQuery<StockRow>(QUERY, { params: { warehouseCode: warehouse ?? null }, limit: 100 }),
  })

  const columns = useMemo<ColumnDef<StockRow, unknown>[]>(() => {
    const right = { className: 'text-right tabular-nums' }
    return [
      { id: 'warehouse', header: t('stock.warehouse'), cell: ({ row }) => row.original.warehouseCode },
      { id: 'sku', header: t('stock.sku'), cell: ({ row }) => row.original.sku },
      { id: 'product', header: t('stock.product'), cell: ({ row }) => row.original.productName },
      { id: 'onHand', header: t('stock.onHand'), meta: right, cell: ({ row }) => row.original.onHand },
      { id: 'reserved', header: t('stock.reserved'), meta: right, cell: ({ row }) => row.original.reserved },
      { id: 'available', header: t('stock.available'), meta: right, cell: ({ row }) => row.original.available },
    ]
  }, [t])

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={t('stock.title')}
        className="pb-0"
        actions={
          <Button variant="outline" asChild>
            <Link to={paths.dataset(RECEIPTS)} data-testid="receipts-link">
              {t('stock.receipts')}
            </Link>
          </Button>
        }
      />
      {can('commerce.stock.receive') && (
        <ReceiveCard onReceived={() => void queryClient.invalidateQueries({ queryKey: ['ext', QUERY] })} />
      )}
      <form
        role="search"
        className="flex max-w-sm items-center gap-2"
        onSubmit={(event) => {
          event.preventDefault()
          setWarehouse(filter.trim() || undefined)
        }}
      >
        <Input
          id={filterId}
          type="search"
          value={filter}
          onChange={(event) => {
            setFilter(event.target.value)
            // Emptied (by hand or the field's clear button): every warehouse again, as antd's allowClear did.
            if (event.target.value.trim() === '') setWarehouse(undefined)
          }}
          placeholder={t('stock.filter')}
          aria-label={t('stock.filter')}
        />
        <Button type="submit" variant="outline">
          <Search aria-hidden />
          {t('stock.search')}
        </Button>
      </form>
      <div data-testid="stock-table">
        <DataTable
          label={t('stock.title')}
          columns={columns}
          getRowId={(row) => `${row.warehouseCode}/${row.sku}`}
          loading={stock.isLoading}
          data={stock.data?.items ?? []}
          empty={stock.error instanceof ApiError ? stock.error.display : t('stock.empty')}
        />
      </div>
    </div>
  )
}

/** A receipt of goods through the process STOCK_RECEIVE. */
function ReceiveCard({ onReceived }: { onReceived: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const form = useForm<ReceiveForm>({ defaultValues: { warehouseCode: '', sku: '', quantity: '' } })
  const busy = form.formState.isSubmitting
  const required = { validate: (value: string) => value.trim() !== '' || t('stock.required') }

  const receive = async (values: ReceiveForm) => {
    try {
      const out = await runProcess<ReceiveOutput>('STOCK_RECEIVE', {
        warehouseCode: values.warehouseCode.trim(),
        sku: values.sku.trim(),
        quantity: Number(values.quantity),
      })
      notify.success(t('stock.received', { sku: out.sku, warehouse: out.warehouseCode, onHand: out.onHand }))
      form.resetField('quantity')
      onReceived()
    } catch (e) {
      notify.error(e instanceof ApiError ? e.display : String(e))
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2 className="text-base font-semibold">{t('stock.receive')}</h2>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <Form {...form}>
          <form
            noValidate
            className="flex flex-wrap items-start gap-3"
            onSubmit={form.handleSubmit(receive)}
            data-testid="receive-form"
          >
            <FormField
              control={form.control}
              name="warehouseCode"
              rules={required}
              render={({ field }) => (
                <FormItem className="w-40">
                  <FormLabel>{t('stock.warehouse')}</FormLabel>
                  <FormControl>
                    <Input {...field} required />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="sku"
              rules={required}
              render={({ field }) => (
                <FormItem className="w-40">
                  <FormLabel>{t('stock.sku')}</FormLabel>
                  <FormControl>
                    <Input {...field} required />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <FormField
              control={form.control}
              name="quantity"
              rules={{
                validate: (value: string) =>
                  value === '' ? t('stock.required') : Number(value) >= 1 || t('stock.atLeastOne'),
              }}
              render={({ field }) => (
                <FormItem className="w-32">
                  <FormLabel>{t('stock.quantity')}</FormLabel>
                  <FormControl>
                    <DecimalInput
                      value={field.value}
                      onChange={field.onChange}
                      onBlur={field.onBlur}
                      name={field.name}
                      scale={0}
                      allowNegative={false}
                      required
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <Button type="submit" disabled={busy} className="mt-[1.375rem]">
              {busy && <Spinner className="text-current" />}
              {t('stock.receive')}
            </Button>
          </form>
        </Form>
      </CardContent>
    </Card>
  )
}
