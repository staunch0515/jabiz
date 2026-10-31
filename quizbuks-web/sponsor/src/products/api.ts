import { api, runProcess, unwrap } from '@jabiz/client'
import { PRODUCT } from './ids'

/* The sponsor's products as the console edits them (Q60b): types after `SponsorProductProcesses`'s records, and its calls. */

export type ProductStatus = 'LISTED' | 'UNLISTED' | 'TAKEN_DOWN'

/** The currencies a price may be in (the server's `qb.currency` dictionary). */
export const PRODUCT_CURRENCIES = ['JPY', 'USD', 'EUR', 'GBP', 'CNY', 'INR'] as const
export const MAX_PRODUCTS = 50
export const MAX_QUIZ_PRODUCTS = 3
export const PRODUCT_NAME_LENGTH = 100
export const PRODUCT_DESCRIPTION_LENGTH = 500
export const SHOP_URL_LENGTH = 300

/** One of the sponsor's own products. */
export interface MyProduct {
  productId: string
  name: string
  description: string | null
  image: string | null
  price: string
  currency: string
  shopUrl: string
  status: ProductStatus
  seq: number
  takedownReason: string | null
  revision: number
}

/** `SponsorProductProcesses.SaveInput`. */
export interface ProductInput {
  productId: string | null
  name: string
  description: string | null
  image: string | null
  price: string
  currency: string
  shopUrl: string
  listed: boolean
  seq: number | null
  baseRevision: number | null
}

/** A link of the sponsor's quiz to one of their products. */
export interface QuizLink {
  questionNo: number
  productId: string
  seq: number
}

type Row = Record<string, unknown>
const text = (value: unknown) => (value == null || value === '' ? null : String(value))

async function ownRows(dataset: string, filters: { field: string; op: string; value: unknown }[] = []): Promise<Row[]> {
  const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: dataset } },
    body: { limit: 200, filters },
  }))
  return (page.items ?? []).map((item) => (item.attributes ?? {}) as Row)
}

/** The caller's products, in their order. */
export async function readMyProducts(): Promise<MyProduct[]> {
  const rows = await ownRows(PRODUCT.myDataset)
  return rows.map((row) => ({
    productId: String(row.productId ?? ''),
    name: String(row.name ?? ''),
    description: text(row.description),
    image: text(row.image),
    price: String(row.price ?? '0'),
    currency: String(row.currency ?? 'JPY'),
    shopUrl: String(row.shopUrl ?? ''),
    status: row.status as ProductStatus,
    seq: Number(row.seq) || 0,
    takedownReason: text(row.takedownReason),
    revision: Number(row.revision) || 0,
  })).sort((a, b) => a.seq - b.seq || a.name.localeCompare(b.name))
}

/** The links of one of the caller's quizzes. */
export async function readMyQuizLinks(quizId: string): Promise<QuizLink[]> {
  const rows = await ownRows(PRODUCT.myLinks, [{ field: 'quizId', op: 'eq', value: quizId }])
  return rows.map((row) => ({ questionNo: Number(row.questionNo) || 0, productId: String(row.productId ?? ''),
    seq: Number(row.seq) || 0 }))
}

export const saveProduct = (input: ProductInput) => runProcess<{ productId: string; status: ProductStatus;
  revision: number }>(PRODUCT.save, { ...input })

export const deleteProduct = (productId: string) => runProcess(PRODUCT.delete, { productId })

export const setQuizProducts = (quizId: string, quizProducts: string[],
  questionProducts: { questionNo: number; productId: string }[]) =>
  runProcess<{ quizId: string; links: number }>(PRODUCT.quizProducts, { quizId, quizProducts, questionProducts })
