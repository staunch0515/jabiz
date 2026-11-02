/*
 * A sponsor's products as takers see them (docs/quizbuks/plans/Q60-sponsor-page.md §5): what the shared
 * `view/ProductCards` draws and both front ends format. The console's editing is in `sponsor/src/products/`, the
 * app's reading in `app/src/share/`.
 */

/** The decimals a price has in a currency: none for the yen, two for the others (`QbSponsorProducts.scaleOf`). */
export const priceScale = (currency: string) => (currency === 'JPY' ? 0 : 2)

/** A listed product as anybody reads it. */
export interface PublicProduct {
  productId: string
  name: string
  description: string | null
  image: string | null
  price: unknown
  currency: string
  shopUrl: string
  seq: number
  /** Q73c: the language its name and description are in (a sponsor's page shows untranslated ones as written). */
  textLanguage?: string | null
}

/** A product a publication shows: with its quiz (question 0) or with the question of that number. */
export interface PublicationProduct extends PublicProduct {
  questionNo: number
}

/**
 * A price as the reader's locale writes it in its currency, with the currency's decimals: the shop's own price, which
 * the platform does not handle (the one exception to showing amounts as points, backend/quizbuks/CLAUDE.md).
 */
export function formatPrice(price: unknown, currency: string, locale?: string): string {
  const amount = Number(price)
  if (!Number.isFinite(amount)) return `${String(price)} ${currency}`
  const digits = priceScale(currency)
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency, minimumFractionDigits: digits,
      maximumFractionDigits: digits }).format(amount)
  } catch {
    return `${amount.toFixed(digits)} ${currency}`
  }
}

/** Only an https link is ever made a link (the server accepts no other; this is the second line). */
export function httpsLink(url: string | null | undefined): string | null {
  return url && /^https:\/\/[^\s"'<>]+$/.test(url) ? url : null
}

/** The quiz's products (question 0) of a publication's, in the sponsor's order. */
export function quizProducts<P extends PublicationProduct>(products: P[] | undefined): P[] {
  return (products ?? []).filter((product) => Number(product.questionNo) === 0)
    .sort((a, b) => Number(a.seq) - Number(b.seq))
}

/** A publication's product for each question, by its number. */
export function questionProducts<P extends PublicationProduct>(products: P[] | undefined): Map<number, P> {
  return new Map((products ?? []).filter((product) => Number(product.questionNo) > 0)
    .map((product) => [Number(product.questionNo), product]))
}
