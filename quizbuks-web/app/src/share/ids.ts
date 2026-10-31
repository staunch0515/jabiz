/** The public templates of a sponsor's page (`queries/qb/public/sponsor-page*.sql`, Q60a, Q60b). */
export const SPONSOR_PAGE_TEMPLATE = {
  page: 'qb.public.sponsor-page',
  publications: 'qb.public.sponsor-page-publications',
  products: 'qb.public.sponsor-page-products',
  /** A live publication's products (its quiz's and its questions'). */
  publicationProducts: 'qb.public.publication-products',
} as const
