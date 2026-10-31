import { ExternalLink, ShoppingBag } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { FileImage } from '../FileImage'
import { formatPrice, httpsLink, type PublicProduct } from '../products'
import { QBVIEW } from './messages'

/**
 * A sponsor's products as takers see them (Q60b): on the sponsor's page, with a publication (its quiz's) and under a
 * question of its result. Each opens the sponsor's own shop in a new tab; a link that is not https is never made one.
 * Part of the shared folder `src/qb`.
 */
export function ProductCards({ products, title, testId = 'products', headingLevel = 'h2' }: {
  products: PublicProduct[]
  /** The block's heading; none: the products alone (under a question, which names them). */
  title?: string
  testId?: string
  headingLevel?: 'h2' | 'h3'
}) {
  const { t, i18n } = useTranslation(QBVIEW)
  if (products.length === 0) return null
  const Heading = headingLevel
  return (
    <section className="flex flex-col gap-2" data-testid={testId} aria-label={title ?? t('products.questionTitle')}>
      {title && (
        <Heading className="flex items-center gap-2 text-base font-semibold">
          <ShoppingBag aria-hidden className="size-4" />{title}
        </Heading>
      )}
      <ul className="flex flex-col gap-2">
        {products.map((product) => {
          const shop = httpsLink(product.shopUrl)
          return (
            <li key={product.productId} className="bg-card flex gap-3 border p-3" data-testid={`${testId}-item`}>
              {product.image && (
                <FileImage fileId={product.image} variant="w256" alt=""
                  className="size-16 shrink-0 border object-cover" />
              )}
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <span className="font-medium">{product.name}</span>
                {product.description && (
                  <span className="text-muted-foreground text-sm whitespace-pre-line">{product.description}</span>
                )}
                <span className="text-sm font-semibold tabular-nums" data-testid={`${testId}-price`}>
                  {formatPrice(product.price, product.currency, i18n.language)}
                </span>
                {shop && (
                  <a href={shop} target="_blank" rel="noopener noreferrer nofollow sponsored"
                    className="text-primary inline-flex items-center gap-1 self-start text-sm underline"
                    aria-label={t('products.shopOf', { name: product.name })} data-testid={`${testId}-shop`}>
                    <ExternalLink aria-hidden className="size-4" />{t('products.shop')}
                  </a>
                )}
              </div>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
