import {
  Alert,
  AlertDescription,
  Button,
  Label,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Question } from '../content/api'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { generalProblem } from '../qb/problems'
import {
  MAX_QUIZ_PRODUCTS,
  readMyProducts,
  readMyQuizLinks,
  setQuizProducts,
  type MyProduct,
  type QuizLink,
} from './api'
import { PRODUCTS_QUERY } from './ProductsPage'

const NONE = 'none'
const linksQuery = (quizId: string) => ['qb', 'quiz-products', quizId] as const

/**
 * The products one of the sponsor's quizzes shows (Q60b): up to {@link MAX_QUIZ_PRODUCTS} on its page, and one under
 * a question of its result, by the question's number. Saved at once for the whole quiz (`QB_QUIZ_PRODUCTS_SET`), not
 * part of its versions: its publications show them without publishing again.
 */
export function QuizProductsPanel({ quizId, questions }: { quizId: string; questions: Question[] }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const products = useQuery({ queryKey: PRODUCTS_QUERY, queryFn: readMyProducts })
  const links = useQuery({ queryKey: linksQuery(quizId), queryFn: () => readMyQuizLinks(quizId) })
  // Here, not in the form: the form starts again from the links a save stores, and the message must outlive it.
  const [done, setDone] = useState(false)
  if (products.isPending || links.isPending) return <PageState kind="loading" />
  if (products.isError || links.isError) return <PageState kind="error" description={tQb('failed')} />
  return (
    <section className="flex max-w-2xl flex-col gap-4" aria-labelledby="quiz-products-title" data-testid="quiz-products">
      <div className="flex flex-col gap-1">
        <h2 id="quiz-products-title" className="text-lg font-semibold">{t('products.quiz.title')}</h2>
        <p className="text-muted-foreground text-sm">{t('products.quiz.intro', { max: MAX_QUIZ_PRODUCTS })}</p>
      </div>
      {products.data.length === 0
        ? <p className="text-muted-foreground text-sm" data-testid="quiz-products-none">{t('products.quiz.noProducts')}</p>
        : <LinksForm key={JSON.stringify(links.data)} quizId={quizId} questions={questions} products={products.data}
          links={links.data} done={done} setDone={setDone} />}
    </section>
  )
}

function LinksForm({ quizId, questions, products, links, done, setDone }: {
  quizId: string
  questions: Question[]
  products: MyProduct[]
  links: QuizLink[]
  done: boolean
  setDone: (done: boolean) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [slots, setSlots] = useState<string[]>(() => {
    const chosen = links.filter((link) => link.questionNo === 0).sort((a, b) => a.seq - b.seq)
      .map((link) => link.productId)
    return Array.from({ length: MAX_QUIZ_PRODUCTS }, (_, i) => chosen[i] ?? NONE)
  })
  const [byQuestion, setByQuestion] = useState<Record<number, string>>(() => Object.fromEntries(
    links.filter((link) => link.questionNo > 0).map((link) => [link.questionNo, link.productId])))
  // The draft's questions, and any number a link holds beyond them (a question removed since): shown so it can go.
  const linked = links.filter((link) => link.questionNo > questions.length).map((link) => link.questionNo)
  const rows = Math.max(questions.length, ...linked, 0)
  const save = useMutation({
    mutationFn: () => setQuizProducts(quizId, slots.filter((slot) => slot !== NONE),
      Object.entries(byQuestion).filter(([, product]) => product !== NONE)
        .map(([no, product]) => ({ questionNo: Number(no), productId: product }))),
    onSuccess: async () => {
      setDone(true)
      await queryClient.invalidateQueries({ queryKey: linksQuery(quizId) })
    },
  })
  const options = (
    <SelectContent>
      <SelectItem value={NONE}>{t('products.quiz.noneOption')}</SelectItem>
      {products.map((product) => <SelectItem key={product.productId} value={product.productId}>{product.name}</SelectItem>)}
    </SelectContent>
  )
  const general = generalProblem(save.error, [])
  return (
    <form noValidate className="flex flex-col gap-4" data-testid="quiz-products-form" onSubmit={(event) => {
      event.preventDefault()
      setDone(false)
      save.mutate()
    }}>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 font-medium">{t('products.quiz.quizProducts')}</legend>
        {slots.map((slot, i) => (
          <div key={i} className="flex items-center gap-3">
            <Label htmlFor={`${id}-slot-${i}`} className="w-24 font-normal">{t('products.quiz.slot', { n: i + 1 })}</Label>
            <Select value={slot} onValueChange={(value) => {
              setDone(false)
              setSlots((current) => current.map((s, j) => (j === i ? value : s)))
            }}>
              <SelectTrigger id={`${id}-slot-${i}`} className="w-64" data-testid={`quiz-product-slot-${i + 1}`}>
                <SelectValue />
              </SelectTrigger>
              {options}
            </Select>
          </div>
        ))}
      </fieldset>
      {rows > 0 && (
        <fieldset className="flex flex-col gap-2" aria-describedby={`${id}-question-hint`}>
          <legend className="mb-1 font-medium">{t('products.quiz.questionProducts')}</legend>
          <p id={`${id}-question-hint`} className="text-muted-foreground text-sm">{t('products.quiz.questionHint')}</p>
          {Array.from({ length: rows }, (_, i) => {
            const no = i + 1
            const question: Question | undefined = questions[i]
            return (
              <div key={question?.questionId ?? `no-${no}`} className="flex flex-wrap items-center gap-3">
                <Label htmlFor={`${id}-q-${no}`} className="w-24 font-normal">{t('products.quiz.question', { n: no })}</Label>
                <Select value={byQuestion[no] ?? NONE} onValueChange={(value) => {
                  setDone(false)
                  setByQuestion((current) => ({ ...current, [no]: value }))
                }}>
                  <SelectTrigger id={`${id}-q-${no}`} className="w-64" data-testid={`question-product-${no}`}>
                    <SelectValue />
                  </SelectTrigger>
                  {options}
                </Select>
                {question?.stem && <span className="text-muted-foreground min-w-0 flex-1 truncate text-sm">{question.stem}</span>}
                {!question && <span className="text-muted-foreground text-sm" data-testid={`question-product-${no}-gone`}>
                  {t('products.quiz.gone')}</span>}
              </div>
            )
          })}
        </fieldset>
      )}
      {general && (
        <Alert variant="destructive" data-testid="quiz-products-error"><AlertDescription>{general}</AlertDescription></Alert>
      )}
      {done && !save.isPending && (
        <p role="status" className="text-success flex items-center gap-2 text-sm" data-testid="quiz-products-done">
          <CircleCheck aria-hidden className="size-4" />{t('products.quiz.saved')}
        </p>
      )}
      <Button type="submit" className="self-start" disabled={save.isPending} aria-busy={save.isPending || undefined}
        data-testid="quiz-products-save">{t('products.quiz.save')}</Button>
    </form>
  )
}
