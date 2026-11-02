import { formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
  PageState,
  Textarea,
  cn,
  notify,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Flag, MessagesSquare } from 'lucide-react'
import { useEffect, useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link, navigate } from '../qb/router'
import { TitleBadge } from '../pages/TitleBadge'
import { reportConversation } from '../report/api'
import { REPORT_NOTE_LENGTH } from '../report/ids'
import {
  blockSponsor,
  closeConversation,
  CONVERSATIONS_PATH,
  CONVERSATIONS_QUERY,
  conversationPath,
  markRead,
  openConversation,
  readBlocks,
  readConversations,
  readConversationsUnread,
  readMessages,
  sendMessage,
  unblockSponsor,
  type Conversation,
} from './api'
import { MESSAGE_LENGTH } from './ids'

/**
 * The taker's conversations with sponsors (Q82): each about one quiz, newest first, with the sponsor's company and
 * whether they wrote since; and the sponsors the taker blocked, to unblock.
 */
export function ConversationsPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const list = useQuery({ queryKey: [...CONVERSATIONS_QUERY, 'list'], queryFn: () => readConversations() })
  if (list.isPending) return <PageState kind="loading" />
  if (list.isError) {
    return <PageState kind="error" description={problemText(list.error)}
      action={<Button variant="outline" onClick={() => void list.refetch()}>{tQb('retry')}</Button>} />
  }
  return (
    <div className="flex flex-col gap-4" data-testid="conversations">
      <h1 className="flex items-center gap-2 text-2xl font-semibold">
        <TitleBadge icon={MessagesSquare} />
        {t('conversation.list.title')}
      </h1>
      <p className="text-muted-foreground text-sm">{t('conversation.list.intro')}</p>
      {list.data.length === 0 ? (
        <p className="text-muted-foreground text-sm" data-testid="conversations-empty">{t('conversation.list.empty')}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {list.data.map((conversation) => <ConversationRow key={conversation.conversationId}
            conversation={conversation} />)}
        </ul>
      )}
      <BlockedSponsors />
    </div>
  )
}

function ConversationRow({ conversation }: { conversation: Conversation }) {
  const { t } = useTranslation(APP)
  return (
    <li>
      <Link to={conversationPath(conversation.conversationId)} data-testid="conversation-row"
        data-unread={conversation.unread ? 'true' : 'false'}
        className="hover:bg-accent flex flex-col gap-1 rounded-lg border p-3">
        <span className="flex items-center gap-2">
          <span className={cn('min-w-0 flex-1 truncate', conversation.unread && 'font-semibold')}>
            {conversation.sponsorCompany || t('conversation.sponsor')}
          </span>
          {conversation.unread && <Badge data-testid="conversation-unread">{t('conversation.list.new')}</Badge>}
          {conversation.status === 'CLOSED' && <Badge variant="outline">{t('conversation.closed')}</Badge>}
        </span>
        <span className="text-muted-foreground truncate text-sm">{conversation.publicationName}</span>
        {conversation.lastBody && <span className="truncate text-sm">{conversation.lastBody}</span>}
        <span className="text-muted-foreground text-xs">{formatDateTime(conversation.lastMessageAt)}</span>
      </Link>
    </li>
  )
}

/** The sponsors the taker blocked (D-Q82-6): unblocking lets them write again (and the taker reopen). */
function BlockedSponsors() {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  const blocks = useQuery({ queryKey: [...CONVERSATIONS_QUERY, 'blocks'], queryFn: readBlocks })
  const unblock = useMutation({
    mutationFn: unblockSponsor,
    onSuccess: () => {
      notify.success(t('conversation.blocked.removed'))
      void queryClient.invalidateQueries({ queryKey: CONVERSATIONS_QUERY })
    },
    onError: (error) => notify.error(problemText(error)),
  })
  if (!blocks.data || blocks.data.length === 0) return null
  return (
    <section aria-labelledby="conversation-blocks-title" className="flex flex-col gap-2" data-testid="conversation-blocks">
      <h2 id="conversation-blocks-title" className="text-lg font-semibold">{t('conversation.blocked.title')}</h2>
      <ul className="flex flex-col gap-2">
        {blocks.data.map((block) => (
          <li key={block.blockId} className="flex items-center gap-2 rounded-lg border p-3" data-testid="conversation-block">
            <span className="min-w-0 flex-1 truncate">{block.sponsorCompany || t('conversation.sponsor')}</span>
            <Button variant="outline" size="sm" disabled={unblock.isPending}
              onClick={() => unblock.mutate(block.blockId)} data-testid="conversation-unblock">
              {t('conversation.blocked.unblock')}
            </Button>
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * One conversation (Q82): the messages in order (the taker's on one side, the sponsor's on the other), a box to
 * write, and closing, reporting and blocking. Opening it marks it read.
 */
export function ConversationPage({ conversationId }: { conversationId: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const key = [...CONVERSATIONS_QUERY, 'one', conversationId]
  const conversation = useQuery({ queryKey: key, queryFn: async () =>
    (await readConversations({ conversationId }))[0] ?? null })
  const messages = useQuery({ queryKey: [...key, 'messages'], queryFn: () => readMessages(conversationId) })
  const unread = conversation.data?.unread === true
  useEffect(() => {
    if (!unread) return
    void markRead(conversationId).then(() => queryClient.invalidateQueries({ queryKey: CONVERSATIONS_QUERY }),
      () => undefined)
  }, [unread, conversationId, queryClient])
  if (conversation.isPending || messages.isPending) return <PageState kind="loading" />
  if (conversation.isError || messages.isError) {
    return <PageState kind="error" description={problemText(conversation.error ?? messages.error)}
      action={<Button variant="outline" onClick={() => {
        void conversation.refetch()
        void messages.refetch()
      }}>{tQb('retry')}</Button>} />
  }
  const data = conversation.data
  if (!data) return <PageState kind="notFound" titleAs="h1" description={tQb('notFound')} />
  const refresh = () => queryClient.invalidateQueries({ queryKey: CONVERSATIONS_QUERY })
  return (
    <section className="flex flex-col gap-4" aria-labelledby="conversation-title" data-testid="conversation">
      <header className="flex flex-col gap-1">
        <Link to={CONVERSATIONS_PATH} className="text-muted-foreground text-sm">{t('conversation.list.title')}</Link>
        <h1 id="conversation-title" className="text-xl font-semibold" data-testid="conversation-sponsor">
          {data.sponsorCompany || t('conversation.sponsor')}
        </h1>
        <Link to={`/p/${data.publicationId}`} className="text-muted-foreground text-sm underline-offset-4 hover:underline">
          {data.publicationName}
        </Link>
      </header>
      <p className="text-muted-foreground text-xs">{t('conversation.notice')}</p>
      <ol className="flex flex-col gap-2" aria-label={t('conversation.messages')} data-testid="conversation-messages">
        {messages.data.map((message) => {
          const mine = message.sender === 'TAKER'
          return (
            <li key={message.messageId} data-testid="conversation-message" data-sender={message.sender}
              className={cn('flex max-w-[85%] flex-col gap-1 rounded-lg border p-3',
                mine ? 'bg-accent self-end' : 'self-start')}>
              <span className="text-muted-foreground text-xs">
                {mine ? t('conversation.you') : data.sponsorCompany || t('conversation.sponsor')}
                {' · '}{formatDateTime(message.createdAt)}
              </span>
              <span className="text-sm whitespace-pre-wrap break-words">
                {message.body ?? <em className="text-muted-foreground">{t('conversation.erased')}</em>}
              </span>
            </li>
          )
        })}
      </ol>
      {data.status === 'OPEN' ? (
        <Composer onSend={(body) => sendMessage(conversationId, body)} onSent={refresh} />
      ) : (
        <div className="flex flex-col gap-2 rounded-lg border p-3" data-testid="conversation-closed">
          <p className="text-sm">{t(`conversation.closedBy.${data.closedBy ?? 'TAKER'}`)}</p>
          <Composer label={t('conversation.reopen')} onSend={(body) => openConversation(data.publicationId, body)}
            onSent={refresh} />
        </div>
      )}
      <div className="flex flex-wrap gap-2">
        {data.status === 'OPEN' && <CloseButton conversationId={conversationId} onDone={refresh} />}
        <ReportConversation publicationId={data.publicationId} conversationId={conversationId} />
        <BlockButton conversationId={conversationId} company={data.sponsorCompany} onDone={refresh} />
      </div>
    </section>
  )
}

/** The box to write in: the server checks the text (no addresses, so many a day) and says why not. */
function Composer({ onSend, onSent, label }: { onSend: (body: string) => Promise<unknown>; onSent: () => void;
  label?: string }) {
  const { t } = useTranslation(APP)
  const id = useId()
  const [body, setBody] = useState('')
  const send = useMutation({
    mutationFn: () => onSend(body),
    onSuccess: () => {
      setBody('')
      onSent()
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (body.trim()) send.mutate()
  }
  return (
    <form className="flex flex-col gap-2" onSubmit={submit} data-testid="conversation-composer">
      <Label htmlFor={`${id}-body`}>{label ?? t('conversation.write')}</Label>
      <Textarea id={`${id}-body`} value={body} maxLength={MESSAGE_LENGTH} rows={3}
        onChange={(event) => setBody(event.target.value)} data-testid="conversation-body" />
      <p className="text-muted-foreground text-xs">{t('conversation.plainText')}</p>
      {send.isError && (
        <p className="text-destructive text-sm" role="alert" data-testid="conversation-error">{problemText(send.error)}</p>
      )}
      <Button type="submit" className="self-end" disabled={!body.trim() || send.isPending}
        data-testid="conversation-send">{t('conversation.send')}</Button>
    </form>
  )
}

/** A confirmation dialog around one action. */
function Confirm({ trigger, title, description, action, testId, onConfirm, pending }: {
  trigger: string; title: string; description: string; action: string; testId: string; onConfirm: () => void;
  pending: boolean }) {
  const { t } = useTranslation(APP)
  const [open, setOpen] = useState(false)
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <Button type="button" variant="outline" size="sm" onClick={() => setOpen(true)} data-testid={`${testId}-open`}>
        {trigger}
      </Button>
      <DialogContent data-testid={`${testId}-dialog`}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => setOpen(false)}>{t('report.cancel')}</Button>
          <Button type="button" disabled={pending} data-testid={`${testId}-confirm`} onClick={() => {
            onConfirm()
            setOpen(false)
          }}>{action}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CloseButton({ conversationId, onDone }: { conversationId: string; onDone: () => void }) {
  const { t } = useTranslation(APP)
  const close = useMutation({ mutationFn: () => closeConversation(conversationId), onSuccess: onDone,
    onError: (error) => notify.error(problemText(error)) })
  return <Confirm trigger={t('conversation.close')} title={t('conversation.closeTitle')}
    description={t('conversation.closeHint')} action={t('conversation.close')} testId="conversation-close"
    pending={close.isPending} onConfirm={() => close.mutate()} />
}

function BlockButton({ conversationId, company, onDone }: { conversationId: string; company: string;
  onDone: () => void }) {
  const { t } = useTranslation(APP)
  const block = useMutation({
    mutationFn: () => blockSponsor(conversationId),
    onSuccess: () => {
      notify.success(t('conversation.blocked.done'))
      onDone()
    },
    onError: (error) => notify.error(problemText(error)),
  })
  return <Confirm trigger={t('conversation.block')} title={t('conversation.blockTitle', { company })}
    description={t('conversation.blockHint')} action={t('conversation.block')} testId="conversation-block"
    pending={block.isPending} onConfirm={() => block.mutate()} />
}

/** Reports what the sponsor wrote (reason MESSAGE, naming the conversation) to the quiz's reviewers. */
function ReportConversation({ publicationId, conversationId }: { publicationId: string; conversationId: string }) {
  const { t } = useTranslation(APP)
  const id = useId()
  const [open, setOpen] = useState(false)
  const [note, setNote] = useState('')
  const send = useMutation({
    mutationFn: () => reportConversation(publicationId, conversationId, note),
    onSuccess: () => {
      notify.success(t('report.sent'))
      setOpen(false)
      setNote('')
    },
  })
  return (
    <Dialog open={open} onOpenChange={(next) => { setOpen(next); if (!next) send.reset() }}>
      <Button type="button" variant="ghost" size="sm" className="text-muted-foreground"
        onClick={() => setOpen(true)} data-testid="conversation-report-open">
        <Flag aria-hidden className="size-4" />
        {t('conversation.report')}
      </Button>
      <DialogContent data-testid="conversation-report-dialog">
        <form className="flex flex-col gap-4" onSubmit={(event) => { event.preventDefault(); send.mutate() }}>
          <DialogHeader>
            <DialogTitle>{t('conversation.report')}</DialogTitle>
            <DialogDescription>{t('conversation.reportHint')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-note`}>{t('report.note')}</Label>
            <Textarea id={`${id}-note`} value={note} maxLength={REPORT_NOTE_LENGTH} rows={3}
              onChange={(event) => setNote(event.target.value)} data-testid="conversation-report-note" />
          </div>
          {send.isError && <p className="text-destructive text-sm" role="alert">{problemText(send.error)}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>{t('report.cancel')}</Button>
            <Button type="submit" disabled={send.isPending} data-testid="conversation-report-send">
              {t('report.send')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * The result page's way to the sponsor (Q82, D-Q82-3): "Contact the sponsor" writes the first message and opens the
 * conversation; once there is one, "View the conversation". Shown for a completed attempt of a sponsor's quiz.
 */
export function ContactSponsor({ publicationId, company }: { publicationId: string; company: string | null }) {
  const { t } = useTranslation(APP)
  const id = useId()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [body, setBody] = useState('')
  const existing = useQuery({ queryKey: [...CONVERSATIONS_QUERY, 'about', publicationId],
    queryFn: async () => (await readConversations({ publicationId }))[0] ?? null })
  const send = useMutation({
    mutationFn: () => openConversation(publicationId, body),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: CONVERSATIONS_QUERY })
      setOpen(false)
      navigate(conversationPath(result.conversationId))
    },
  })
  if (existing.isPending || existing.isError) return null
  if (existing.data) {
    return (
      <Button asChild variant="outline" data-testid="result-contact-sponsor" data-existing="true">
        <Link to={conversationPath(existing.data.conversationId)}>
          <MessagesSquare aria-hidden className="size-4" />
          {t('conversation.view')}
        </Link>
      </Button>
    )
  }
  return (
    <Dialog open={open} onOpenChange={(next) => { setOpen(next); if (!next) send.reset() }}>
      <Button type="button" variant="outline" onClick={() => setOpen(true)} data-testid="result-contact-sponsor">
        <MessagesSquare aria-hidden className="size-4" />
        {t('conversation.contact')}
      </Button>
      <DialogContent data-testid="contact-sponsor-dialog">
        <form className="flex flex-col gap-4" onSubmit={(event) => {
          event.preventDefault()
          if (body.trim()) send.mutate()
        }}>
          <DialogHeader>
            <DialogTitle>{t('conversation.contactTitle', { company: company || t('conversation.sponsor') })}</DialogTitle>
            <DialogDescription>{t('conversation.contactHint')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-body`}>{t('conversation.write')}</Label>
            <Textarea id={`${id}-body`} value={body} maxLength={MESSAGE_LENGTH} rows={4}
              onChange={(event) => setBody(event.target.value)} data-testid="contact-sponsor-body" />
            <p className="text-muted-foreground text-xs">{t('conversation.plainText')}</p>
          </div>
          {send.isError && (
            <p className="text-destructive text-sm" role="alert" data-testid="contact-sponsor-error">
              {problemText(send.error)}</p>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>{t('report.cancel')}</Button>
            <Button type="submit" disabled={!body.trim() || send.isPending} data-testid="contact-sponsor-send">
              {t('conversation.send')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** The inbox's way to the conversations (Q82), with how many have a reply not read yet. */
export function ConversationsLink() {
  const { t } = useTranslation(APP)
  const unread = useQuery({ queryKey: [...CONVERSATIONS_QUERY, 'unread'], queryFn: readConversationsUnread,
    refetchOnWindowFocus: false })
  return (
    <Link to={CONVERSATIONS_PATH} data-testid="inbox-conversations"
      className="hover:bg-accent flex items-center gap-2 rounded-lg border p-3 text-sm">
      <MessagesSquare aria-hidden className="size-4" />
      <span className="flex-1">{t('conversation.list.title')}</span>
      {(unread.data ?? 0) > 0 && (
        <Badge data-testid="inbox-conversations-unread">{t('conversation.list.unread', { count: unread.data })}</Badge>
      )}
    </Link>
  )
}
