import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  ConfirmDialog,
  Label,
  PageState,
  Textarea,
  cn,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { History, Loader2, MessageSquarePlus, ThumbsDown, ThumbsUp, UserRound } from 'lucide-react'
import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import {
  askHelp,
  closeHelp,
  DEFAULT_HELP_SETTINGS,
  escalateHelp,
  HELP_QUERY,
  helpLanguage,
  helpProblem,
  pendingQuestion,
  plainAnswer,
  rateHelp,
  readHelpMessages,
  readHelpSettings,
  readHelpThread,
  readHelpThreads,
  siteLinks,
  type HelpEntry,
  type HelpMessage,
  type HelpProblem,
  type HelpThread,
} from './help'
import './helpMessages'
import { HELP_CODE } from './ids'
import { QB } from './messages'
import { problemText } from './problems'
import { BASE, Link } from './router'

/** Where the first question's notice is remembered as read, per entry (this browser only). */
const NOTICE_KEY = 'qb.help.notice.'

function noticeRead(entry: HelpEntry): boolean {
  try {
    return window.localStorage.getItem(NOTICE_KEY + entry) === '1'
  } catch {
    return false
  }
}

function rememberNotice(entry: HelpEntry) {
  try {
    window.localStorage.setItem(NOTICE_KEY + entry, '1')
  } catch {
    // Shown again next time: nothing is lost.
  }
}

export interface HelpChatProps {
  entry: HelpEntry
  /** The current page's name on the entry's list (`help.HelpPages`), or null: never a path. */
  page: string | null
  /** The conversation shown; null for a new one. */
  threadId: string | null
  onThreadChange: (threadId: string | null) => void
  /** Whether the component shows its own heading and introduction (a panel has its title and description). */
  heading?: boolean
  /** Told when a link of an answer leads to another page of this SPA (a panel closes). */
  onFollowLink?: () => void
}

/** The problem shown above the box: what kind, and the server's words. */
interface Shown {
  kind: HelpProblem
  text: string
}

/**
 * The AI help's conversation (F-C10c; docs/quizbuks/plans/impl/F-C10.md): asking, waiting for the answer (asking again
 * every `qb.help.poll-seconds` for at most `qb.help.poll-max-minutes`), the answer as plain text with the site's
 * links, rating it, handing the conversation (or, when the AI is off or a limit is reached, the question) to a person,
 * ending it, and the caller's earlier conversations. Answers and replies are announced to screen readers; everything
 * works from the keyboard. A 409 (the conversation changed meanwhile) reads it again and keeps what was typed.
 */
export function HelpChat({ entry, page, threadId, onThreadChange, heading = true, onFollowLink }: HelpChatProps) {
  const { t, i18n } = useTranslation(QB)
  const id = useId()
  const client = useQueryClient()
  const [noticeSeen, setNoticeSeen] = useState(() => noticeRead(entry))
  const [showHistory, setShowHistory] = useState(false)
  const [draft, setDraft] = useState('')
  const [problem, setProblem] = useState<Shown | null>(null)
  const [announcement, setAnnouncement] = useState('')
  const [timedOutFor, setTimedOutFor] = useState<string | null>(null)
  const [round, setRound] = useState(0)
  const box = useRef<HTMLTextAreaElement>(null)

  const settingsQuery = useQuery({ queryKey: [...HELP_QUERY, 'settings'], queryFn: readHelpSettings,
    staleTime: 60_000 })
  const settings = settingsQuery.data ?? DEFAULT_HELP_SETTINGS
  const thread = useQuery({ queryKey: [...HELP_QUERY, 'thread', entry, threadId], enabled: !!threadId,
    queryFn: () => readHelpThread(entry, threadId!) })
  const messagesQuery = useQuery({
    queryKey: [...HELP_QUERY, 'messages', threadId],
    enabled: !!threadId,
    queryFn: () => readHelpMessages(threadId!),
    refetchInterval: (query) => {
      const waiting = pendingQuestion(query.state.data)
      return waiting && timedOutFor !== waiting.messageId ? settings.pollSeconds * 1000 : false
    },
  })
  const messages = threadId ? messagesQuery.data : undefined
  const current: HelpThread | null = threadId ? (thread.data ?? null) : null
  const pending = pendingQuestion(messages)
  const pendingId = pending?.messageId ?? null
  const timedOut = pendingId !== null && timedOutFor === pendingId

  // Asks no longer than qb.help.poll-max-minutes for one answer; "check again" asks for as long again.
  useEffect(() => {
    if (!pendingId) return undefined
    const timer = window.setTimeout(() => setTimedOutFor(pendingId), settings.pollMaxMinutes * 60_000)
    return () => window.clearTimeout(timer)
  }, [pendingId, settings.pollMaxMinutes, round])

  const failureText = (message: HelpMessage) =>
    message.jobStatus === 'CANCELLED' ? t('helpAi.cancelled')
      : message.failureCode === HELP_CODE.quizRefused ? t('helpAi.quizRefused') : t('helpAi.failed')

  // An answer or a reply that came while the conversation was open is read out (aria-live); so is a failure.
  const known = useRef<{ threadId: string | null; keys: Set<string> }>({ threadId: null, keys: new Set() })
  useEffect(() => {
    if (!threadId || !messages) return
    const keys = messages.map((m) => `${m.messageId}:${m.jobStatus ?? ''}`)
    if (known.current.threadId !== threadId) {
      // A conversation opened (not one just asked in): what it holds is not news.
      known.current = { threadId, keys: new Set(keys) }
      return
    }
    const fresh = messages.filter((m) => !known.current.keys.has(`${m.messageId}:${m.jobStatus ?? ''}`))
    keys.forEach((key) => known.current.keys.add(key))
    const said = [...fresh].reverse().find((m) => m.kind === 'ANSWER' || m.kind === 'STAFF'
      || (m.kind === 'QUESTION' && (m.jobStatus === 'FAILED' || m.jobStatus === 'CANCELLED')))
    if (!said) return
    if (said.kind === 'ANSWER') setAnnouncement(t('helpAi.announceAnswer', { text: plainAnswer(said.body) }))
    else if (said.kind === 'STAFF') setAnnouncement(t('helpAi.announceReply', { text: said.body ?? '' }))
    else setAnnouncement(failureText(said))
    // eslint-disable-next-line react-hooks/exhaustive-deps -- read out once per change of the messages
  }, [messages, threadId])

  const refresh = () => {
    void client.invalidateQueries({ queryKey: HELP_QUERY })
  }

  /** A refusal: a 409 reads the conversation again (what was typed stays in the box); others say why. */
  const refused = (error: unknown) => {
    const kind = helpProblem(error)
    if (kind === 'conflict') {
      setProblem({ kind, text: t('helpAi.conflict') })
      refresh()
    } else {
      setProblem({ kind, text: problemText(error) })
      if (kind === 'closed' || kind === 'waiting') refresh()
    }
    box.current?.focus()
  }

  /** A conversation the caller writes in next: one just made is news from its start (its answer is read out). */
  const select = (next: string | null, made = false) => {
    if (made && next) known.current = { threadId: next, keys: new Set() }
    setTimedOutFor(null)
    onThreadChange(next)
  }

  const language = helpLanguage(i18n.language)
  const writable = !current || current.status !== 'CLOSED'
  const toPerson = current?.status === 'ESCALATED'
  const aiOff = settingsQuery.isSuccess && !settings.enabled && !toPerson

  const ask = useMutation({
    mutationFn: (text: string) => askHelp({ threadId: current ? threadId : null, entry, language, page, text }),
    onSuccess: (output) => {
      setDraft('')
      setProblem(null)
      if (output.threadId !== threadId) select(output.threadId, true)
      refresh()
    },
    onError: refused,
  })
  const escalate = useMutation({
    mutationFn: () => escalateHelp({ threadId: current && current.status === 'OPEN' ? threadId : null, entry,
      language, page, text: draft }),
    onSuccess: (output) => {
      setDraft('')
      setProblem(null)
      if (output.threadId !== threadId) select(output.threadId, true)
      refresh()
    },
    onError: refused,
  })
  const rate = useMutation({
    mutationFn: ({ messageId, rating }: { messageId: string; rating: 'SOLVED' | 'UNSOLVED' }) =>
      rateHelp(threadId!, messageId, rating),
    onSuccess: () => {
      setProblem(null)
      refresh()
    },
    onError: refused,
  })
  const close = useMutation({ mutationFn: () => closeHelp(threadId!), onSuccess: refresh, onError: refused })
  const busy = ask.isPending || escalate.isPending

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const text = draft.trim()
    if (!text || busy) return
    if (aiOff) escalate.mutate()
    else ask.mutate(text)
  }

  const time = (value: string) => {
    const at = new Date(value)
    return Number.isNaN(at.getTime()) ? '' : at.toLocaleString(i18n.language, { dateStyle: 'medium',
      timeStyle: 'short' })
  }

  /** "Ask a person": the conversation (with what was typed), or the typed question alone when there is none. */
  const escalateButton = (testId: string, label = t('helpAi.escalate')) => (
    <Button type="button" variant="outline" size="sm" disabled={busy || (!current && draft.trim() === '')}
      aria-busy={escalate.isPending || undefined} onClick={() => escalate.mutate()} data-testid={testId}>
      <UserRound aria-hidden />
      {label}
    </Button>
  )

  const titleId = `${id}-title`
  const max = settings.questionMaxLength
  const length = [...draft].length

  let body
  if (!noticeSeen) {
    body = (
      <Alert role="note" data-testid="help-ai-notice">
        <AlertDescription className="flex flex-col gap-3">
          <strong className="text-foreground">{t('helpAi.notice.title')}</strong>
          <span>{t('helpAi.notice.body')}</span>
          <Button type="button" className="w-fit" data-testid="help-ai-notice-ok" onClick={() => {
            rememberNotice(entry)
            setNoticeSeen(true)
          }}>{t('helpAi.notice.ok')}</Button>
        </AlertDescription>
      </Alert>
    )
  } else if (showHistory) {
    body = <HelpHistory entry={entry} time={time} onOpen={(next) => {
      setShowHistory(false)
      setProblem(null)
      select(next)
    }} onBack={() => setShowHistory(false)} />
  } else if (threadId && (thread.isError || messagesQuery.isError)) {
    body = <PageState kind="error" description={problemText(thread.error ?? messagesQuery.error)}
      action={<Button variant="outline" onClick={refresh}>{t('retry')}</Button>} />
  } else if (threadId && (thread.isPending || messagesQuery.isPending)) {
    body = <PageState kind="loading" />
  } else if (threadId && !current) {
    body = <p className="text-muted-foreground text-sm" data-testid="help-ai-not-found">{t('helpAi.notFound')}</p>
  } else {
    const lastReply = [...(messages ?? [])].reverse().find((m) => m.kind === 'ANSWER' || m.kind === 'STAFF')
    body = (
      <div className="flex flex-col gap-3">
        {current?.status === 'ESCALATED' && (
          <Alert role="note" data-testid="help-ai-escalated">
            <AlertDescription>
              {current.waitingFor === 'USER' ? t('helpAi.staffReplied') : t('helpAi.escalated')}
            </AlertDescription>
          </Alert>
        )}
        {messages && messages.length > 0 && (
          <ol className="flex flex-col gap-2" aria-label={t('helpAi.messages')} data-testid="help-ai-messages">
            {messages.map((message) => (
              <MessageItem key={message.messageId} message={message} time={time(message.createdAt)}
                onFollowLink={onFollowLink}
                failure={message.kind === 'QUESTION' && (message.jobStatus === 'FAILED'
                  || message.jobStatus === 'CANCELLED') ? failureText(message) : null}
                rating={writable && (message.kind === 'ANSWER' || message.kind === 'STAFF') && !message.rating
                  ? (rating) => rate.mutate({ messageId: message.messageId, rating }) : undefined}
                ratingBusy={rate.isPending} />
            ))}
          </ol>
        )}
        {pending && !timedOut && (
          <p className="text-muted-foreground flex items-center gap-2 text-sm" aria-busy data-testid="help-ai-waiting">
            <Loader2 aria-hidden className="size-4 animate-spin motion-reduce:animate-none" />
            {t('helpAi.waiting')}
          </p>
        )}
        {pending && timedOut && (
          <div className="flex flex-col gap-2 rounded-md border p-3 text-sm" data-testid="help-ai-slow">
            <p>{t('helpAi.waitingLong')}</p>
            <div className="flex flex-wrap gap-2">
              <Button type="button" variant="outline" size="sm" data-testid="help-ai-check-again" onClick={() => {
                setTimedOutFor(null)
                setRound((r) => r + 1)
                void messagesQuery.refetch()
              }}>{t('helpAi.checkAgain')}</Button>
              {current?.status === 'OPEN' && escalateButton('help-ai-slow-escalate')}
            </div>
          </div>
        )}
        {current?.status === 'OPEN' && lastReply?.kind === 'ANSWER' && lastReply.rating === 'UNSOLVED'
          && !pending && (
          <div className="flex flex-wrap items-center gap-2 text-sm" data-testid="help-ai-unsolved">
            <span>{t('helpAi.unsolvedHint')}</span>
            {escalateButton('help-ai-unsolved-escalate')}
          </div>
        )}
        {current?.status === 'CLOSED' && (
          <p className="text-muted-foreground text-sm" data-testid="help-ai-closed">{t('helpAi.closedNote')}</p>
        )}
        {aiOff && !problem && (
          <Alert variant="warning" role="note" data-testid="help-ai-off">
            <AlertDescription>{t('helpAi.off')}</AlertDescription>
          </Alert>
        )}
        {problem && (
          <Alert variant={problem.kind === 'conflict' ? 'warning' : 'destructive'} data-testid="help-ai-problem"
            data-kind={problem.kind}>
            <AlertDescription className="flex flex-col gap-2">
              <span>{problem.kind === 'off' ? t('helpAi.off') : problem.kind === 'limit' ? t('helpAi.limit')
                : problem.kind === 'conflict' ? t('helpAi.conflict')
                  : problem.kind === 'waiting' ? t('helpAi.waitingProblem')
                    : problem.kind === 'closed' ? t('helpAi.closedProblem') : t('helpAi.otherProblem')}</span>
              {problem.kind !== 'conflict' && problem.text !== t('failed') && (
                <span className="text-xs" data-testid="help-ai-problem-detail">{problem.text}</span>
              )}
              {(problem.kind === 'off' || problem.kind === 'limit' || problem.kind === 'other') && !toPerson
                && writable && escalateButton('help-ai-problem-escalate', t('helpAi.escalateNow'))}
            </AlertDescription>
          </Alert>
        )}
        {writable && (
          <form noValidate onSubmit={submit} className="flex flex-col gap-2" data-testid="help-ai-form">
            <Label htmlFor={`${id}-text`}>{toPerson ? t('helpAi.toPerson') : t('helpAi.question')}</Label>
            <Textarea id={`${id}-text`} ref={box} value={draft} rows={3} maxLength={max}
              aria-describedby={`${id}-count`} data-testid="help-ai-text"
              onChange={(event) => setDraft(event.target.value)} />
            <p id={`${id}-count`} className="text-muted-foreground text-xs" data-testid="help-ai-count">
              {t('helpAi.questionHint', { max })} {t('helpAi.count', { count: length, max })}
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <Button type="submit" disabled={busy || draft.trim() === '' || length > max
                || (!!pending && !toPerson && !aiOff)} aria-busy={busy || undefined} data-testid="help-ai-submit">
                {busy ? t('helpAi.sending') : aiOff ? t('helpAi.escalate') : toPerson ? t('helpAi.send')
                  : t('helpAi.ask')}
              </Button>
              {!toPerson && !aiOff && escalateButton('help-ai-escalate')}
              {current && current.status !== 'CLOSED' && (
                <ConfirmDialog title={t('helpAi.closeTitle')} description={t('helpAi.closeText')}
                  confirmLabel={t('helpAi.closeConfirm')} cancelLabel={t('helpAi.cancel')}
                  onConfirm={() => close.mutateAsync()}
                  trigger={<Button type="button" variant="ghost" size="sm" data-testid="help-ai-close">
                    {t('helpAi.close')}</Button>} />
              )}
            </div>
            {!toPerson && !aiOff && (
              <p className="text-muted-foreground text-xs">{t('helpAi.escalateHint')}</p>
            )}
          </form>
        )}
      </div>
    )
  }

  return (
    <section className="flex flex-col gap-3" aria-labelledby={heading ? titleId : undefined}
      aria-label={heading ? undefined : t('helpAi.title')} data-testid="help-ai" data-thread={threadId ?? ''}>
      {heading && (
        <div className="flex flex-col gap-1">
          <h2 id={titleId} className="font-semibold">{t('helpAi.title')}</h2>
          <p className="text-muted-foreground text-sm">{t('helpAi.intro')}</p>
        </div>
      )}
      {noticeSeen && !showHistory && (
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="outline" size="sm" data-testid="help-ai-history"
            onClick={() => setShowHistory(true)}>
            <History aria-hidden />
            {t('helpAi.history')}
          </Button>
          {threadId && (
            <Button type="button" variant="outline" size="sm" data-testid="help-ai-new" onClick={() => {
              setProblem(null)
              select(null)
            }}>
              <MessageSquarePlus aria-hidden />
              {t('helpAi.newConversation')}
            </Button>
          )}
        </div>
      )}
      {body}
      <div className="sr-only" aria-live="polite" aria-atomic="true" data-testid="help-ai-live">{announcement}</div>
    </section>
  )
}

/** One message: who wrote it, the text (plain), an answer's links, its rating. */
function MessageItem({ message, time, failure, rating, ratingBusy, onFollowLink }: {
  message: HelpMessage
  time: string
  failure: string | null
  rating?: (rating: 'SOLVED' | 'UNSOLVED') => void
  ratingBusy: boolean
  onFollowLink?: () => void
}) {
  const { t } = useTranslation(QB)
  const mine = message.kind === 'QUESTION' || message.kind === 'USER'
  const who = message.kind === 'QUESTION' ? t('helpAi.you') : message.kind === 'USER' ? t('helpAi.youToStaff')
    : message.kind === 'ANSWER' ? t('helpAi.ai') : t('helpAi.staff')
  const links = message.kind === 'ANSWER' ? siteLinks(message.links, BASE) : []
  const text = message.kind === 'ANSWER' ? plainAnswer(message.body) : (message.body ?? '')
  return (
    <li className={cn('flex flex-col gap-1 rounded-lg border p-3', mine ? 'bg-muted ml-6' : 'bg-card mr-6')}
      data-testid="help-ai-message" data-kind={message.kind} data-job={message.jobStatus ?? ''}>
      <span className="text-muted-foreground flex flex-wrap gap-2 text-xs">
        <span className="font-medium">{who}</span>
        <span>{time}</span>
      </span>
      {/* Plain text: an answer is never rendered as markup (F-C10). */}
      <p className="text-sm break-words whitespace-pre-wrap" data-testid="help-ai-body">{text}</p>
      {failure && <p className="text-destructive text-sm" data-testid="help-ai-failure">{failure}</p>}
      {links.length > 0 && (
        <div className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground text-xs">{t('helpAi.links')}</span>
          <ul className="flex flex-col gap-1" data-testid="help-ai-links">
            {links.map((link) => (
              <li key={link.href}>
                {link.inApp
                  ? <Link to={link.inApp} onNavigate={onFollowLink} className="text-primary break-all underline"
                    data-testid="help-ai-link">{link.href}</Link>
                  : <a href={link.href} className="text-primary break-all underline" data-testid="help-ai-link">
                    {link.href}</a>}
              </li>
            ))}
          </ul>
        </div>
      )}
      {message.rating && (
        <p className="text-muted-foreground text-xs" data-testid="help-ai-rated">{t(`helpAi.rated.${message.rating}`)}</p>
      )}
      {rating && (
        <div role="group" aria-label={t('helpAi.rate')} className="flex flex-wrap items-center gap-2"
          data-testid="help-ai-rate">
          <span className="text-muted-foreground text-xs">{t('helpAi.rate')}</span>
          <Button type="button" variant="outline" size="sm" disabled={ratingBusy} data-testid="help-ai-solved"
            onClick={() => rating('SOLVED')}>
            <ThumbsUp aria-hidden />
            {t('helpAi.solved')}
          </Button>
          <Button type="button" variant="outline" size="sm" disabled={ratingBusy} data-testid="help-ai-unsolved-rate"
            onClick={() => rating('UNSOLVED')}>
            <ThumbsDown aria-hidden />
            {t('helpAi.unsolved')}
          </Button>
        </div>
      )}
    </li>
  )
}

/** The caller's conversations of the entry, newest first; one opens on choosing it. */
function HelpHistory({ entry, time, onOpen, onBack }: {
  entry: HelpEntry
  time: (value: string) => string
  onOpen: (threadId: string) => void
  onBack: () => void
}) {
  const { t } = useTranslation(QB)
  const threads = useQuery({ queryKey: [...HELP_QUERY, 'threads', entry], queryFn: () => readHelpThreads(entry) })
  return (
    <div className="flex flex-col gap-3" data-testid="help-ai-threads">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">{t('helpAi.history')}</h3>
        <Button type="button" variant="ghost" size="sm" onClick={onBack} data-testid="help-ai-history-back">
          {t('helpAi.historyBack')}
        </Button>
      </div>
      {threads.isPending && <PageState kind="loading" />}
      {threads.isError && <PageState kind="error" description={problemText(threads.error)}
        action={<Button variant="outline" onClick={() => void threads.refetch()}>{t('retry')}</Button>} />}
      {threads.isSuccess && threads.data.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="help-ai-threads-empty">{t('helpAi.historyEmpty')}</p>
      )}
      {threads.isSuccess && threads.data.length > 0 && (
        <ul className="flex flex-col gap-2">
          {threads.data.map((row) => (
            <li key={row.threadId}>
              <button type="button" onClick={() => onOpen(row.threadId)} data-testid="help-ai-thread"
                data-thread={row.threadId}
                className="hover:bg-accent focus-visible:ring-ring/50 flex w-full flex-col gap-1 rounded-md border p-3
                  text-left outline-none focus-visible:ring-[3px]">
                <span className="line-clamp-2 text-sm">{row.firstQuestion ?? ''}</span>
                <span className="text-muted-foreground flex flex-wrap items-center gap-2 text-xs">
                  <Badge variant={row.status === 'CLOSED' ? 'outline' : row.status === 'ESCALATED' ? 'secondary'
                    : 'default'}>{t(`helpAi.statuses.${row.status}`)}</Badge>
                  {t('helpAi.lastActivity', { time: time(row.lastActivityAt) })}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
