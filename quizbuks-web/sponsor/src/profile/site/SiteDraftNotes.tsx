import { Alert, AlertDescription, Button, Label } from '@jabiz/ui'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { reasonsOf } from '../../content/QuizTransfer'
import { quizPath } from '../../content/paths'
import { SPONSOR } from '../../messages'
import type { ContentLanguage } from '../../qb/api'
import { QB } from '../../qb/messages'
import { navigate } from '../../qb/router'
import { makeSiteQuiz, SITE_QUERY, type SiteDraft } from './api'
import './messages'


const record = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}

/** A text by language (or one plain text) as the lines to show: the language and the text. */
function linesOf(value: unknown, base: ContentLanguage): { language: ContentLanguage; text: string }[] {
  if (typeof value === 'string') return value ? [{ language: base, text: value }] : []
  return (Object.entries(record(value)) as [ContentLanguage, unknown][])
    .filter(([, text]) => typeof text === 'string' && text !== '')
    .map(([language, text]) => ({ language, text: String(text) }))
}

interface Field { path: string; label: string; lines: { language: ContentLanguage; text: string }[] }

/** Only an address the website's own pages can have is shown as a link: https, nothing else. */
const safeLink = (url: string | null): string | null => (url && /^https:\/\//i.test(url) ? url : null)

/**
 * What the AI found, for the review (F-C7b): each field with the page of the website it was taken from, the texts it
 * translated marked as machine-translated, what the website did not show, what was left out or changed and why, and,
 * when the drafted link name is another sponsor's, the free ones to choose from. The usual import's plan follows.
 */
export function SiteDraftNotes({ draft, handle, onHandle }: {
  draft: SiteDraft
  /** The link name in force: the drafted one unless the sponsor chose another. */
  handle: string | null
  onHandle: (handle: string) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const queryClient = useQueryClient()
  const [quizProblems, setQuizProblems] = useState<string[]>([])
  // F-C7b-2: the "get to know us" quiz, from the draft's articles; its questions wait in the quiz for review.
  const quiz = useMutation({
    mutationFn: () => makeSiteQuiz(draft.aiJobId),
    onSuccess: async (made) => {
      await queryClient.invalidateQueries({ queryKey: SITE_QUERY })
      await queryClient.invalidateQueries({ queryKey: ['qb'] })
      navigate(quizPath(made.quizId))
    },
    onError: (error) => setQuizProblems(reasonsOf(error, t)),
  })
  const base = draft.languages[0] ?? draft.language
  const basics = record(draft.profile?.basics)
  const page = record(draft.profile?.page)
  const products = Array.isArray(draft.profile?.products) ? draft.profile.products.map(record) : []
  const fields: Field[] = [
    { path: 'basics.company', label: t('site.review.company'), lines: linesOf(basics.company, base) },
    { path: 'basics.intro', label: t('site.review.intro'), lines: linesOf(basics.intro, base) },
    { path: 'page.headline', label: t('site.review.headline'), lines: linesOf(page.headline, base) },
    { path: 'page.website', label: t('site.review.website'), lines: linesOf(page.website, base) },
    ...products.flatMap((product, i): Field[] => [
      { path: `products[${i}].name`, label: t('site.review.productName', { n: i + 1 }), lines: linesOf(product.name, base) },
      { path: `products[${i}].price`, label: t('site.review.productPrice', { n: i + 1 }),
        lines: product.price == null ? [] : [{ language: base, text: `${String(product.price)} ${String(product.currency ?? '')}` }] },
    ]),
  ].filter((field) => field.lines.length > 0)
  const taken = draft.handle?.taken ?? false
  return (
    <section className="flex flex-col gap-3 rounded-md border p-3 text-sm" aria-labelledby={`${id}-title`}
      data-testid="site-notes">
      <h3 id={`${id}-title`} className="font-medium">{t('site.review.title', { address: draft.address })}</h3>
      <p className="text-muted-foreground">{t('site.review.hint')}</p>
      <ul className="flex flex-col gap-2" data-testid="site-fields">
        {fields.map((field) => {
          const source = safeLink(draft.sources[field.path] ?? null)
          return (
            <li key={field.path} className="flex flex-col gap-0.5" data-testid="site-field" data-path={field.path}>
              <span className="font-medium">{field.label}</span>
              {field.lines.map((line) => (
                <span key={line.language} lang={line.language} className="break-words">
                  <span className="text-muted-foreground">{tQb(`languages.${line.language}`)}: </span>
                  {line.text}
                  {draft.translated.includes(line.language) && line.language !== base && (
                    <span className="text-muted-foreground" data-testid="site-machine"> ({t('site.review.machine')})</span>
                  )}
                </span>
              ))}
              {source
                ? <a href={source} target="_blank" rel="noopener noreferrer" className="break-all underline"
                    data-testid="site-source">{t('site.review.source', { url: source })}</a>
                : <span className="text-muted-foreground">{t('site.review.noSource')}</span>}
            </li>
          )
        })}
      </ul>
      {taken && draft.handle && (
        <Alert variant="destructive" data-testid="site-handle">
          <AlertDescription className="flex flex-col gap-2">
            <span role="alert">{t('site.review.handleTaken', { handle: draft.handle.handle })}</span>
            {draft.handle.alternatives.length > 0
              ? (
                <fieldset className="flex flex-col gap-1">
                  <legend className="font-medium">{t('site.review.handleChoose')}</legend>
                  {draft.handle.alternatives.map((alternative) => (
                    <span key={alternative} className="flex items-center gap-2">
                      <input type="radio" id={`${id}-${alternative}`} name={`${id}-handle`} value={alternative}
                        checked={handle === alternative} data-testid={`site-handle-${alternative}`}
                        onChange={() => onHandle(alternative)} />
                      <Label htmlFor={`${id}-${alternative}`}>{alternative}</Label>
                    </span>
                  ))}
                </fieldset>
              )
              : <span>{t('site.review.handleNone')}</span>}
          </AlertDescription>
        </Alert>
      )}
      {draft.notFound.length > 0 && (
        <div data-testid="site-not-found">
          <p className="font-medium">{t('site.review.notFound')}</p>
          <ul className="list-disc pl-5">{draft.notFound.map((path) => <li key={path}>{path}</li>)}</ul>
        </div>
      )}
      {draft.dropped.length > 0 && (
        <div data-testid="site-dropped">
          <p className="font-medium">{t('site.review.dropped')}</p>
          <ul className="list-disc pl-5">
            {draft.dropped.map((field) => (
              <li key={`${field.path}-${field.reason}`}>
                {field.path}: {t(`site.review.reason.${reasonKey(field.reason)}`, { defaultValue: field.reason })}
              </li>
            ))}
          </ul>
        </div>
      )}
      {draft.materials > 0 && (
        <div className="flex flex-col gap-2" data-testid="site-quiz">
          <p className="text-muted-foreground">{t('site.review.quiz', { n: draft.materials, q: draft.questions })}</p>
          {draft.quizMade
            ? <p role="status" data-testid="site-quiz-made">{t('site.review.quizMade')}</p>
            : (
              <>
                <p>{t('site.review.quizHint')}</p>
                <span>
                  <Button type="button" variant="outline" size="sm" disabled={quiz.isPending} data-testid="site-quiz-make"
                    onClick={() => {
                      setQuizProblems([])
                      quiz.mutate()
                    }}>
                    {t('site.review.quizMake')}
                  </Button>
                </span>
              </>
            )}
          {quizProblems.length > 0 && (
            <Alert variant="destructive" data-testid="site-quiz-problems">
              <AlertDescription>
                <ul className="list-disc pl-5">{quizProblems.map((problem, i) => <li key={i}>{problem}</li>)}</ul>
              </AlertDescription>
            </Alert>
          )}
        </div>
      )}
    </section>
  )
}

/** The server's reasons for a field it changed or left out that this page words itself. */
function reasonKey(reason: string): string {
  if (reason === 'Contact details were removed') return 'contact'
  if (reason === "Not on the website's own domain") return 'offSite'
  if (reason === 'Not one of the languages asked for') return 'otherLanguage'
  return 'other'
}
