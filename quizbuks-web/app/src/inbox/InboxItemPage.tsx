import { formatDateTime, i18n } from '@jabiz/client'
import { Button, MarkdownView, PageState } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { pickText } from '../qb/legal'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link, navigate } from '../qb/router'
import { INBOX_PATH, INBOX_QUERY, markItem, readInboxItem, targetOf, type ItemKind } from './api'
import { senderOf } from './InboxPage'
import './messages'

/**
 * One item of the inbox (U-62, U-63): read when opened (the server records the first reading, Q7-14). QuizBuks's texts
 * are Markdown (`MarkdownView`, no raw HTML); a sponsor's notice is plain text, never rendered, and its only way out
 * is its quiz ("View quiz", which records the click before going there). Only an item still in the taker's inbox is
 * found: a withdrawn or expired one is gone.
 */
export function InboxItemPage({ kind, id }: { kind: ItemKind; id: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const item = useQuery({ queryKey: [...INBOX_QUERY, 'item', kind, id], queryFn: () => readInboxItem(kind, id) })
  const marked = useRef(false)
  const read = useMutation({
    mutationFn: () => markItem(kind, id, 'READ'),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: [...INBOX_QUERY, 'unread'] }),
  })
  const click = useMutation({ mutationFn: () => markItem(kind, id, 'CLICK') })
  const found = item.data
  useEffect(() => {
    if (found && !found.readAt && !marked.current) {
      marked.current = true
      read.mutate()
    }
  }, [found, read])

  const back = (
    <Link to={INBOX_PATH} className="text-primary inline-flex items-center gap-1 text-sm underline"
      data-testid="inbox-back">
      <ArrowLeft aria-hidden className="size-4" />
      {t('inbox.back')}
    </Link>
  )
  if (item.isPending) return <PageState kind="loading" />
  if (item.isError) {
    return <PageState kind="error" description={problemText(item.error)}
      action={<Button variant="outline" onClick={() => void item.refetch()}>{tQb('retry')}</Button>} />
  }
  if (!found) {
    return (
      <section className="flex flex-col gap-4" data-testid="inbox-item-gone">
        {back}
        <PageState kind="notFound" titleAs="h1" description={t('inbox.gone')} />
      </section>
    )
  }
  const title = pickText(found.title, i18n.language)
  const body = pickText(found.body, i18n.language)
  const target = targetOf(found)
  const label = found.itemKind === 'MESSAGE' && found.action ? t(`inbox.action.${found.action}`) : t('inbox.viewQuiz')
  const go = () => {
    if (!target) return
    // The click is recorded first (Q7-14: a conversion is a start after it); a failure does not keep the taker.
    click.mutate(undefined, { onSettled: () => navigate(target) })
  }
  return (
    <article className="flex flex-col gap-4" aria-labelledby="inbox-item-title" data-testid="inbox-item"
      data-kind={found.itemKind}>
      {back}
      <header className="flex flex-col gap-1">
        <h1 id="inbox-item-title" className="text-xl font-semibold" lang={title.lang}>{title.text}</h1>
        <p className="text-muted-foreground flex flex-wrap gap-x-3 text-sm">
          <span data-testid="inbox-item-sender">{senderOf(found, t)}</span>
          <span>{formatDateTime(found.at)}</span>
          {found.expiresAt && <span>{t('inbox.until', { time: formatDateTime(found.expiresAt) })}</span>}
        </p>
      </header>
      {/* Markdown only for the platform's announcements; a message or a notice carries others' words (review H2). */}
      {found.markdown && found.itemKind === 'BROADCAST' && found.sender === 'PLATFORM' ? (
        <MarkdownView text={body.text} lang={body.lang} />
      ) : (
        // A sponsor's text, or a message with a sponsor's publication name, exactly as written: never a link (Q7-10).
        <p className="whitespace-pre-wrap break-words" lang={body.lang} data-testid="inbox-item-plain">{body.text}</p>
      )}
      {target && (
        <div>
          <Button onClick={go} disabled={click.isPending} data-testid="inbox-item-go">
            <ArrowRight aria-hidden />
            {label}
          </Button>
        </div>
      )}
    </article>
  )
}
