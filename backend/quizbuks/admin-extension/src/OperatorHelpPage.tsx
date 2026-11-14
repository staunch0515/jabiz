import { useQuery } from '@tanstack/react-query'
import { ChevronDown } from 'lucide-react'
import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Badge,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  MarkdownView,
  PageHeader,
  PageState,
  useLanguage,
} from '@jabiz/admin'
import { EmptyState, LoadFailed } from './components'
import { useLabels } from './hooks'
import { DICTIONARY } from './ids'
import { OPERATOR_HELP_KEY, pickText, readOperatorFaq, type OperatorFaq } from './operator-help-api'

/** Waits until typing pauses before searching (one request per pause, not per key). */
function useSettled(value: string, delay = 300): string {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = window.setTimeout(() => setSettled(value), delay)
    return () => window.clearTimeout(timer)
  }, [value, delay])
  return settled
}

/** One question, folded: the answer (Markdown without raw HTML) opens under it. */
function Question({ entry, category }: { entry: OperatorFaq; category: string }) {
  const language = useLanguage()
  const question = pickText(entry.question, language)
  const answer = pickText(entry.answer, language)
  return (
    <details className="group rounded-md border" data-testid="operator-faq-entry">
      <summary className="flex cursor-pointer list-none items-center justify-between gap-2 rounded-md p-3 font-medium
        outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50">
        <span className="flex flex-wrap items-center gap-2">
          <span lang={question.lang}>{question.text}</span>
          {category && <Badge variant="outline">{category}</Badge>}
        </span>
        <ChevronDown aria-hidden className="size-4 shrink-0 transition-transform group-open:rotate-180" />
      </summary>
      <div className="border-t px-3 py-2 text-sm" data-testid="operator-faq-answer">
        <MarkdownView text={answer.text} lang={answer.lang} />
      </div>
    </details>
  )
}

/**
 * The operators' help (the help centre's questions of their audience, written and published by the content
 * administrators): searched by text in any language, read after signing in to the Operator Management System.
 */
export default function OperatorHelpPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const searchId = useId()
  const [text, setText] = useState('')
  const q = useSettled(text)
  const label = useLabels(DICTIONARY.faqCategory)
  const faq = useQuery({
    queryKey: [...OPERATOR_HELP_KEY, q],
    queryFn: () => readOperatorFaq(q, null),
  })
  const header = <PageHeader title={t('operatorHelp.title')} description={t('operatorHelp.description')} className="pb-0" />
  return (
    <div className="flex flex-col gap-4" data-testid="operator-help">
      {header}
      <div className="flex max-w-md flex-col gap-1">
        <Label htmlFor={searchId}>{t('operatorHelp.search')}</Label>
        <Input id={searchId} type="search" value={text} onChange={(event) => setText(event.target.value)}
          data-testid="operator-help-search" />
      </div>
      {faq.isPending && <PageState kind="loading" />}
      {faq.isError && <LoadFailed error={faq.error} onRetry={() => void faq.refetch()} />}
      {faq.data && faq.data.length === 0 && (
        <EmptyState title={t(q.trim() === '' ? 'operatorHelp.none' : 'operatorHelp.noMatch')}
          hint={q.trim() === '' ? t('operatorHelp.noneHint') : undefined} testId="operator-help-empty" />
      )}
      {faq.data && faq.data.length > 0 && (
        <div className="flex flex-col gap-2">
          {faq.data.map((entry) => <Question key={entry.faqId} entry={entry} category={label(entry.category)} />)}
        </div>
      )}
    </div>
  )
}
