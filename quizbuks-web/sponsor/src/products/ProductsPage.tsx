import { uploadFile } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Checkbox,
  ConfirmDialog,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  FileUpload,
  Input,
  Label,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
  notify,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Plus } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { neutralName } from '../content/api'
import { TextTranslationsFields, type TextsByLanguage } from '../content/TextTranslationsFields'
import { SPONSOR } from '../messages'
import { FileImage } from '../qb/FileImage'
import { QB } from '../qb/messages'
import { readMySponsorPage } from '../page/api'
import { SPONSOR_PAGE } from '../page/ids'
import { SPONSOR_PAGE_QUERY } from '../page/SponsorPageEditor'
import { formatPrice } from '../qb/products'
import { fieldProblem, generalProblem } from '../qb/problems'
import {
  deleteProduct,
  MAX_PRODUCTS,
  PRODUCT_CURRENCIES,
  PRODUCT_DESCRIPTION_LENGTH,
  PRODUCT_NAME_LENGTH,
  readMyProducts,
  saveProduct,
  SHOP_URL_LENGTH,
  type MyProduct,
  type ProductInput,
} from './api'

export const PRODUCTS_PATH = '/products'
export const PRODUCTS_QUERY = ['qb', 'my-products'] as const

const FIELDS = ['name', 'description', 'image', 'price', 'currency', 'shopUrl', 'baseRevision'] as const
const IMAGE_TYPES = 'image/jpeg,image/png'
const IMAGE_BYTES = 5 * 1024 * 1024

/** What a product's form starts from: the product, or a new one in the reader's likely currency. */
function inputOf(product: MyProduct | null): ProductInput {
  return {
    productId: product?.productId ?? null,
    name: product?.name ?? '',
    description: product?.description ?? null,
    image: product?.image ?? null,
    price: product?.price ?? '',
    currency: product?.currency ?? 'JPY',
    shopUrl: product?.shopUrl ?? '',
    listed: product ? product.status === 'LISTED' : true,
    seq: product?.seq ?? null,
    baseRevision: product?.revision ?? null,
  }
}

/**
 * The sponsor's products (Q60b; docs/quizbuks/plans/Q60-sponsor-page.md §5): each with its image, price in its
 * currency and shop link, in the sponsor's order; added, changed, moved and deleted here (`QB_PRODUCT_SAVE`,
 * `QB_PRODUCT_DELETE`). A product taken down shows why and stays hidden whatever is saved.
 */
export function ProductsPage() {
  const { t, i18n } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const products = useQuery({ queryKey: PRODUCTS_QUERY, queryFn: readMyProducts })
  const [editing, setEditing] = useState<MyProduct | 'new' | null>(null)
  const refresh = () => queryClient.invalidateQueries({ queryKey: PRODUCTS_QUERY })
  const remove = useMutation({
    mutationFn: (productId: string) => deleteProduct(productId),
    onSuccess: async () => {
      notify.success(t('products.deleted'))
      await refresh()
    },
  })
  // Moving swaps two neighbours and numbers the list again from 0, saving each product whose place changed (with the
  // revision the page read): places stay distinct even after deletions and additions.
  const move = useMutation({
    mutationFn: async ({ list, from, to }: { list: MyProduct[]; from: number; to: number }) => {
      const order = [...list]
      ;[order[from], order[to]] = [order[to], order[from]]
      for (const [place, product] of order.entries()) {
        if (product.seq !== place) await saveProduct({ ...inputOf(product), seq: place })
      }
    },
    onSettled: refresh,
  })

  const list = products.data ?? []
  return (
    <section className="flex max-w-3xl flex-col gap-6" aria-labelledby="products-title" data-testid="products">
      <div className="flex flex-col gap-2">
        <h1 id="products-title" className="text-xl font-semibold">{t('products.title')}</h1>
        <p className="text-muted-foreground text-sm">{t('products.intro', { max: MAX_PRODUCTS })}</p>
      </div>
      {products.isPending && <PageState kind="loading" />}
      {products.isError && <PageState kind="error" description={tQb('failed')} />}
      {products.isSuccess && (
        <>
          <div className="flex flex-wrap items-center gap-3">
            <Button disabled={list.length >= MAX_PRODUCTS} data-testid="product-add" onClick={() => setEditing('new')}>
              <Plus aria-hidden />{t('products.add')}
            </Button>
            {list.length >= MAX_PRODUCTS && (
              <span className="text-muted-foreground text-sm">{t('products.full', { max: MAX_PRODUCTS })}</span>
            )}
          </div>
          {(remove.error || move.error) && (
            <Alert variant="destructive" data-testid="products-error">
              <AlertDescription>{generalProblem(remove.error ?? move.error, [])}</AlertDescription>
            </Alert>
          )}
          {list.length === 0 ? <p className="text-muted-foreground text-sm" data-testid="products-none">{t('products.none')}</p> : (
            <ul className="flex flex-col gap-2">
              {list.map((product, index) => (
                <li key={product.productId} className="bg-card flex gap-3 border p-3" data-testid="product"
                  data-status={product.status}>
                  {product.image && <FileImage fileId={product.image} variant="w256" alt=""
                    className="size-16 shrink-0 border object-cover" />}
                  <div className="flex min-w-0 flex-1 flex-col gap-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{product.name}</span>
                      <Badge variant={product.status === 'LISTED' ? 'success'
                        : product.status === 'TAKEN_DOWN' ? 'destructive' : 'secondary'}>
                        {t(`products.status.${product.status}`)}
                      </Badge>
                    </div>
                    <span className="text-sm tabular-nums">{formatPrice(product.price, product.currency, i18n.language)}</span>
                    <span className="text-muted-foreground text-sm break-all">{product.shopUrl}</span>
                    {product.status === 'TAKEN_DOWN' && product.takedownReason && (
                      <span className="text-destructive text-sm" data-testid="product-takedown">
                        {t('products.takedownReason', { reason: product.takedownReason })}
                      </span>
                    )}
                  </div>
                  <div className="flex shrink-0 flex-col items-end gap-1">
                    <div className="flex gap-1">
                      <Button variant="ghost" size="icon" disabled={index === 0 || move.isPending}
                        aria-label={t('products.up', { name: product.name })}
                        onClick={() => move.mutate({ list, from: index, to: index - 1 })}>
                        <ArrowUp aria-hidden />
                      </Button>
                      <Button variant="ghost" size="icon" disabled={index === list.length - 1 || move.isPending}
                        aria-label={t('products.down', { name: product.name })}
                        onClick={() => move.mutate({ list, from: index, to: index + 1 })}>
                        <ArrowDown aria-hidden />
                      </Button>
                    </div>
                    <div className="flex gap-2">
                      <Button variant="outline" size="sm" data-testid="product-edit" onClick={() => setEditing(product)}>
                        {t('products.edit')}
                      </Button>
                      <ConfirmDialog destructive title={t('products.deleteTitle')} description={t('products.deleteText')}
                        confirmLabel={t('products.delete')} cancelLabel={t('products.cancel')}
                        onConfirm={() => remove.mutate(product.productId)}
                        trigger={<Button variant="outline" size="sm" disabled={remove.isPending}
                          data-testid="product-delete">{t('products.delete')}</Button>} />
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      {editing && (
        <ProductDialog key={editing === 'new' ? 'new' : editing.productId}
          product={editing === 'new' ? null : editing} seq={Math.max(-1, ...list.map((p) => p.seq)) + 1}
          onClose={() => setEditing(null)} onSaved={async () => {
            setEditing(null)
            notify.success(t('products.saved'))
            await refresh()
          }} />
      )}
    </section>
  )
}

function ProductDialog({ product, seq, onClose, onSaved }: {
  product: MyProduct | null
  /** A new product's place: last. */
  seq: number
  onClose: () => void
  onSaved: () => void
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [form, setForm] = useState<ProductInput>(() => ({ ...inputOf(product), seq: product ? product.seq : seq }))
  // Q73c: the page's other languages, into which the product may be translated (optional).
  const page = useQuery({ queryKey: SPONSOR_PAGE_QUERY, queryFn: readMySponsorPage })
  const others = (page.data?.languages ?? []).filter((l) => l !== page.data?.language)
  const [translations, setTranslations] = useState<TextsByLanguage>(product?.translations ?? {})
  const [confirmed, setConfirmed] = useState<string[]>([])
  const [uploading, setUploading] = useState(0)
  const set = <K extends keyof ProductInput>(key: K, value: ProductInput[K]) =>
    setForm((current) => ({ ...current, [key]: value }))
  const save = useMutation({
    mutationFn: () => saveProduct({ ...form, name: form.name.trim(), price: form.price.trim(),
      shopUrl: form.shopUrl.trim(), description: form.description?.trim() ? form.description.trim() : null,
      // The page's languages' translations given, others (of languages the page no longer offers) kept as stored.
      translations: { ...product?.translations, ...Object.fromEntries(others.map((l) => [l, translations[l] ?? {}])) },
      confirmed }),
    onSuccess: onSaved,
  })
  const problem = (field: string) => fieldProblem(save.error, field)
  const errorOf = (field: string) => problem(field)
    && <p id={`${id}-${field}-error`} className="text-destructive text-sm">{problem(field)}</p>
  const describedBy = (field: string, hint?: string) =>
    [hint, problem(field) ? `${id}-${field}-error` : undefined].filter(Boolean).join(' ') || undefined
  const invalid = (field: string) => (problem(field) ? true : undefined)
  const general = generalProblem(save.error, [...FIELDS, ...others.flatMap((l) =>
    [`translations.${l}.name`, `translations.${l}.description`])])
  const submit = (event: FormEvent) => {
    event.preventDefault()
    save.mutate()
  }
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg" data-testid="product-dialog">
        <DialogHeader>
          <DialogTitle>{product ? t('products.dialogEdit') : t('products.dialogNew')}</DialogTitle>
          <DialogDescription>{t('products.intro', { max: MAX_PRODUCTS })}</DialogDescription>
        </DialogHeader>
        <form id={`${id}-form`} noValidate onSubmit={submit} className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-name`}>{t('products.name')}</Label>
            <Input id={`${id}-name`} value={form.name} required maxLength={PRODUCT_NAME_LENGTH}
              aria-invalid={invalid('name')} aria-describedby={describedBy('name')} data-testid="product-name"
              onChange={(event) => set('name', event.target.value)} />
            {errorOf('name')}
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-description`}>{t('products.description')}</Label>
            <Textarea id={`${id}-description`} value={form.description ?? ''} rows={3}
              maxLength={PRODUCT_DESCRIPTION_LENGTH} aria-describedby={describedBy('description', `${id}-description-hint`)}
              data-testid="product-description" onChange={(event) => set('description', event.target.value)} />
            <p id={`${id}-description-hint`} className="text-muted-foreground text-sm">{t('products.descriptionHint')}</p>
          </div>
          {others.length > 0 && (
            <div className="flex flex-col gap-2" data-testid="product-languages">
              <p className="text-muted-foreground text-sm">{t('pageLanguages.productHint')}</p>
              <TextTranslationsFields id={`${id}-tr`} languages={others} value={translations}
                hashes={product?.translationHashes ?? {}} confirmed={confirmed} onChange={setTranslations}
                onConfirmed={setConfirmed} problemOf={problem} testId="product-translations"
                fields={[
                  { field: 'name', label: t('products.name'), source: form.name, maxLength: PRODUCT_NAME_LENGTH },
                  { field: 'description', label: t('products.description'), source: form.description ?? '',
                    maxLength: PRODUCT_DESCRIPTION_LENGTH, multiline: true },
                ]} />
            </div>
          )}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-image`}>{t('products.image')}</Label>
            {form.image && (
              <div className="flex items-center gap-3">
                <FileImage fileId={form.image} variant="w256" alt={t('products.imageAlt')}
                  className="size-16 border object-cover" />
                <Button type="button" variant="outline" size="sm" onClick={() => set('image', null)}>
                  {t('products.removeImage')}
                </Button>
              </div>
            )}
            <FileUpload id={`${id}-image`} accept={IMAGE_TYPES} maxSize={IMAGE_BYTES} maxSizeText="5 MB"
              label={t('products.upload')} hint={t('products.imageHint')} aria-invalid={invalid('image')}
              aria-describedby={describedBy('image')} data-testid="product-image"
              onUpload={async (file) => {
                setUploading((count) => count + 1)
                try {
                  const uploaded = await uploadFile(SPONSOR_PAGE.imagePolicy, file, neutralName('image', file.name))
                  set('image', uploaded.fileId ?? null)
                } finally {
                  setUploading((count) => count - 1)
                }
              }} />
            {errorOf('image')}
          </div>
          <div className="flex flex-wrap gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-price`}>{t('products.price')}</Label>
              <Input id={`${id}-price`} value={form.price} inputMode="decimal" className="w-36" required
                aria-invalid={invalid('price')} aria-describedby={describedBy('price', `${id}-price-hint`)}
                data-testid="product-price" onChange={(event) => set('price', event.target.value)} />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-currency`}>{t('products.currency')}</Label>
              <Select value={form.currency} onValueChange={(value) => set('currency', value)}>
                <SelectTrigger id={`${id}-currency`} className="w-32" data-testid="product-currency">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PRODUCT_CURRENCIES.map((code) => <SelectItem key={code} value={code}>{code}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
          </div>
          <p id={`${id}-price-hint`} className="text-muted-foreground -mt-2 text-sm">{t('products.priceHint')}</p>
          {errorOf('price')}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-shop`}>{t('products.shopUrl')}</Label>
            <Input id={`${id}-shop`} type="url" inputMode="url" value={form.shopUrl} required maxLength={SHOP_URL_LENGTH}
              placeholder="https://" aria-invalid={invalid('shopUrl')}
              aria-describedby={describedBy('shopUrl', `${id}-shop-hint`)} data-testid="product-shop"
              onChange={(event) => set('shopUrl', event.target.value)} />
            <p id={`${id}-shop-hint`} className="text-muted-foreground text-sm">{t('products.shopUrlHint')}</p>
            {errorOf('shopUrl')}
          </div>
          <div className="flex items-center gap-2">
            <Checkbox id={`${id}-listed`} checked={form.listed} data-testid="product-listed"
              disabled={product?.status === 'TAKEN_DOWN'}
              onCheckedChange={(value) => set('listed', value === true)} />
            <Label htmlFor={`${id}-listed`} className="font-normal">{t('products.listed')}</Label>
          </div>
          {general && (
            <Alert variant="destructive" data-testid="product-error"><AlertDescription>{general}</AlertDescription></Alert>
          )}
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('products.cancel')}</Button>
          <Button type="submit" form={`${id}-form`} disabled={save.isPending || uploading > 0}
            aria-busy={save.isPending || undefined} data-testid="product-save">{t('products.save')}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
