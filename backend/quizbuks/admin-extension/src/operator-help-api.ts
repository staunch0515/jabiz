import { runQuery } from '@jabiz/admin'
import { TEMPLATE } from './ids'

type Row = Record<string, unknown>

/** One question of the operators' help: the texts by language, as the content administrators wrote them. */
export interface OperatorFaq {
  faqId: string
  category: string
  question: Record<string, string>
  answer: Record<string, string>
}

const texts = (value: unknown): Record<string, string> => {
  const out: Record<string, string> = {}
  if (value && typeof value === 'object') {
    for (const [language, text] of Object.entries(value as Record<string, unknown>)) {
      if (typeof text === 'string' && text.trim() !== '') out[language] = text
    }
  }
  return out
}

/** The language shown: the reader's, else English (always written), else any that there is. */
export function pickText(text: Record<string, string>, language: string): { text: string; lang: string } {
  const short = language.split('-')[0] ?? language
  for (const code of [language, short, 'en']) {
    if (text[code]) return { text: text[code], lang: code }
  }
  const [first] = Object.entries(text)
  return first ? { text: first[1], lang: first[0] } : { text: '', lang: language }
}

export const OPERATOR_HELP_KEY = ['qb-ext', 'operator-help'] as const

/** `qb.operator.faq`: the published questions of the operators' audience, in their order, those containing `q`. */
export async function readOperatorFaq(q: string, category: string | null): Promise<OperatorFaq[]> {
  const params: Record<string, string> = {}
  if (q.trim() !== '') params.q = q.trim()
  if (category) params.category = category
  const page = await runQuery<Row>(TEMPLATE.operatorFaq, { limit: 200, params })
  return page.items.map((row) => ({
    faqId: String(row.faqId),
    category: String(row.category ?? ''),
    question: texts(row.question),
    answer: texts(row.answer),
  }))
}
