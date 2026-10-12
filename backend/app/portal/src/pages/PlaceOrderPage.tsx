import { formatAmount, isProblem, runProcess, runQuery, useAuth } from '@jabiz/client'
import { Alert, AlertDescription, Button, Input, Label, PageState, VerifyEmailBanner } from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { PORTAL } from '../messages'

interface ProductRow {
  warehouseCode: string
  sku: string
  productName: string
  unitPrice: string | number
}

/**
 * Orders for the customer's own account (`PORTAL_ORDER_PLACE`): the server picks the customer from the session and
 * requires a verified address; until then the page says so and offers the link again.
 */
export function PlaceOrderPage() {
  const { t } = useTranslation(PORTAL)
  const { emailVerified } = useAuth()
  const queryClient = useQueryClient()
  const productId = useId()
  const quantityId = useId()
  const [choice, setChoice] = useState('')
  const [quantity, setQuantity] = useState('1')
  const [busy, setBusy] = useState(false)
  const [placed, setPlaced] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const products = useQuery({
    queryKey: ['portal', 'products'],
    queryFn: () => runQuery<ProductRow>('commerce.portal_products', { limit: 200 }),
  })

  async function submit(event: FormEvent) {
    event.preventDefault()
    const product = products.data?.items.find((p) => `${p.warehouseCode}/${p.sku}` === choice)
    const amount = Number(quantity)
    if (!product || !Number.isInteger(amount) || amount < 1) return
    setBusy(true)
    setError(null)
    setPlaced(null)
    try {
      const output = await runProcess<{ orderNo: string }>('PORTAL_ORDER_PLACE', {
        warehouseCode: product.warehouseCode,
        lines: [{ sku: product.sku, quantity: amount }],
      })
      setPlaced(output.orderNo)
      await queryClient.invalidateQueries({ queryKey: ['portal', 'orders'] })
    } catch (e) {
      if (isProblem(e, 'EMAIL_NOT_VERIFIED')) setError(t('order.verifyFirst'))
      else if (isProblem(e, 'COMMERCE_PORTAL_NO_CUSTOMER')) setError(t('order.noCustomer'))
      else setError(e instanceof Error && e.message ? e.message : t('failed'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="flex max-w-md flex-col gap-4" aria-labelledby="order-title">
      <h1 id="order-title" className="text-xl font-semibold">
        {t('order.title')}
      </h1>
      {!emailVerified && <VerifyEmailBanner />}
      {products.isPending && <PageState kind="loading" />}
      {products.isError && <PageState kind="error" description={t('failed')} />}
      {products.data && products.data.items.length === 0 && <p>{t('order.none')}</p>}
      {placed && (
        <Alert role="status" data-testid="order-placed">
          <AlertDescription className="text-current">{t('order.placed', { orderNo: placed })}</AlertDescription>
        </Alert>
      )}
      {error && (
        <Alert variant="destructive" data-testid="order-error">
          <AlertDescription className="text-current">{error}</AlertDescription>
        </Alert>
      )}
      {products.data && products.data.items.length > 0 && (
        <form className="flex flex-col gap-4" onSubmit={(event) => void submit(event)} noValidate>
          <div className="flex flex-col gap-2">
            <Label htmlFor={productId}>{t('order.product')}</Label>
            <select
              id={productId}
              required
              value={choice}
              onChange={(event) => setChoice(event.target.value)}
              className="border-input bg-background h-9 rounded-md border px-3 text-sm"
              data-testid="order-product"
            >
              <option value="">{t('order.choose')}</option>
              {products.data.items.map((p) => (
                <option key={`${p.warehouseCode}/${p.sku}`} value={`${p.warehouseCode}/${p.sku}`}>
                  {p.productName} ({p.warehouseCode}) — {formatAmount(p.unitPrice, { currency: 'JPY', scale: 0 })}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={quantityId}>{t('order.quantity')}</Label>
            <Input
              id={quantityId}
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              value={quantity}
              onChange={(event) => setQuantity(event.target.value)}
              data-testid="order-quantity"
            />
          </div>
          <Button type="submit" disabled={busy || !choice} aria-busy={busy || undefined} data-testid="order-submit">
            {t('order.submit')}
          </Button>
        </form>
      )}
    </section>
  )
}
