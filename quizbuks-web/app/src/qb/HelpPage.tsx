import { useOptionalAuth } from '@jabiz/client'
import { Input, Label, MarkdownView, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, Mail } from 'lucide-react'
import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { FAQ_CATEGORIES, pickText, readFaq, readSupportEmail, type FaqAudience, type FaqEntry } from './legal'
import { QB } from './messages'

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
function Question({ entry }: { entry: FaqEntry }) {
  const { i18n: { language } } = useTranslation(QB)
  const question = pickText(entry.question, language)
  const answer = pickText(entry.answer, language)
  return (
    <details className="group rounded-md border" data-testid="faq-entry">
      <summary className="flex cursor-pointer list-none items-center justify-between gap-2 p-3 font-medium
        focus-visible:ring-ring/50 rounded-md outline-none focus-visible:ring-[3px]">
        <span lang={question.lang}>{question.text}</span>
        <ChevronDown aria-hidden className="size-4 shrink-0 transition-transform group-open:rotate-180" />
      </summary>
      <div className="border-t px-3 py-2 text-sm" data-testid="faq-answer">
        <MarkdownView text={answer.text} lang={answer.lang} />
      </div>
    </details>
  )
}

/** "Still need help?": the support address, for signed-in users only (Q7-8), when one is set. */
function Contact() {
  const { t } = useTranslation(QB)
  const auth = useOptionalAuth()
  const signedIn = !!auth?.signedIn
  const email = useQuery({ queryKey: ['qb', 'support'], queryFn: readSupportEmail, enabled: signedIn,
    staleTime: 5 * 60_000 })
  if (signedIn && !email.data) return null
  return (
    <section className="flex flex-col gap-2 rounded-md border p-4" aria-labelledby="help-contact"
      data-testid="help-contact">
      <h2 id="help-contact" className="font-semibold">{t('help.stillNeed')}</h2>
      {signedIn ? (
        <p className="flex items-center gap-2 text-sm">
          <Mail aria-hidden className="size-4" />
          <a href={`mailto:${email.data}`} className="text-primary underline" data-testid="help-mail">
            {t('help.contact', { email: email.data })}
          </a>
        </p>
      ) : (
        <p className="text-muted-foreground text-sm">{t('help.signInToContact')}</p>
      )}
    </section>
  )
}

/**
 * The help centre (U-72, P-05; Q7-7): the published questions for this front end (and those for both), by category,
 * searched in any language, folded. Readable without signing in (public template `qb.public.faq`); the contact
 * address shows to signed-in users only.
 */
export function HelpPage({ audience }: { audience: FaqAudience }) {
  const { t } = useTranslation(QB)
  const id = useId()
  const [search, setSearch] = useState('')
  const q = useSettled(search.trim())
  const faq = useQuery({ queryKey: ['qb', 'faq', audience, q], queryFn: () => readFaq(audience, q) })
  const order = FAQ_CATEGORIES[audience]
  const groups = new Map<string, FaqEntry[]>()
  for (const entry of faq.data ?? []) groups.set(entry.category, [...(groups.get(entry.category) ?? []), entry])
  const categories = [...groups.keys()].sort((a, b) => {
    const rank = (c: string) => (order.indexOf(c) < 0 ? order.length : order.indexOf(c))
    return rank(a) - rank(b)
  })

  return (
    <section className="flex flex-col gap-6" aria-labelledby="help-title" data-testid="help">
      <div className="flex flex-col gap-1">
        <h1 id="help-title" className="text-xl font-semibold">{t('help.title')}</h1>
        <p className="text-muted-foreground text-sm">{t('help.intro')}</p>
      </div>
      <div className="flex flex-col gap-2" role="search">
        <Label htmlFor={`${id}-search`}>{t('help.search')}</Label>
        <Input id={`${id}-search`} type="search" value={search} maxLength={100} data-testid="help-search"
          onChange={(event) => setSearch(event.target.value)} />
      </div>
      {faq.isPending && <PageState kind="loading" />}
      {faq.isError && <PageState kind="error" description={t('failed')} />}
      {faq.isSuccess && faq.data.length === 0 && (
        <PageState kind="empty" description={q ? t('help.empty') : t('help.none')} data-testid="help-empty" />
      )}
      {categories.map((category) => (
        <section key={category} className="flex flex-col gap-2" aria-labelledby={`${id}-${category}`}
          data-testid={`help-category-${category}`}>
          <h2 id={`${id}-${category}`} className="font-semibold">
            {t(`help.categories.${category}`, { defaultValue: category })}
          </h2>
          {groups.get(category)!.map((entry) => <Question key={entry.faqId} entry={entry} />)}
        </section>
      ))}
      <Contact />
    </section>
  )
}
