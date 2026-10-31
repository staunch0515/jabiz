import { countPublic } from '@jabiz/client'
import type { PublicProduct } from '../qb/products'
import { SPONSOR_COUNTER } from './ids'

/**
 * Counts what a visitor does with a sponsor's page and products (Q60c), signed in or not: the server counts each
 * visitor once a day per page or product, and only published pages and listed products. Nothing is shown or awaited.
 */
export function countPageView(handle: string) {
  countPublic(SPONSOR_COUNTER.pageView, handle)
}

/** Counts an opening of a product's shop link; passed to `ProductCards` as `onOpen`. */
export function countProductClick(product: PublicProduct) {
  countPublic(SPONSOR_COUNTER.productClick, product.productId)
}
