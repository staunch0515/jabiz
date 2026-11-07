import { formatAmount, isProblem, runProcess, runQuery, useAuth } from '@jabiz/client'
import { Alert, AlertDescription, FormPage, Input, Label, MarkdownView, PageState, VerifyEmailBanner } from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AREAS } from '../areas'
import { PORTAL } from '../messages'

interface ProductRow {
  warehouseCode: string
  sku: string
  productName: string
  unitPrice: string | number
}

/**
 * Orders for the customer's own account (`PORTAL_ORDER_PLACE`): the server picks the customer from the session and
 * requires a verified address; until then the page says so and offers the link again. On the platform's form
 * template (phase 17zj): one column, and a bar at the bottom that places the order or discards the choice, and says
 * whether something is chosen and not yet ordered.
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

  const dirty = choice !== '' || quantity !== '1'

  async function submit() {
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
      setChoice('')
      setQuantity('1')
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
    <FormPage
      title={t('order.title')}
      icon={AREAS.order.icon}
      dirty={dirty}
      saving={busy}
      canSave={choice !== ''}
      saveLabel={t('order.submit')}
      onSave={() => void submit()}
      onDiscard={() => {
        setChoice('')
        setQuantity('1')
      }}
    >
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
        <>
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
        </>
      )}
      {/* The notes are Markdown in the portal's texts; MarkdownView drops any raw HTML (decision D20). */}
      <MarkdownView text={t('order.howTo')} />
    </FormPage>
  )
}
