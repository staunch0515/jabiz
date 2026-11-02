import { formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  DataTable,
  Label,
  PageState,
  Switch,
  Textarea,
  cn,
  notify,
  type ColumnDef,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useEffect, useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { CASH_REWARDS_PATH } from '../publishing/CashRewardsPage'
import { DIGITAL_PRIZES_PATH } from '../publishing/DigitalPrizesPage'
import { REDEEM_PATH } from '../publishing/RedeemPage'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import {
  closeConversation,
  CONVERSATIONS_PATH,
  CONVERSATIONS_QUERY,
  conversationPath,
  markRead,
  readConversations,
  readMessages,
  readRewards,
  readUnreadConversations,
  sendMessage,
  type ConversationRow,
} from './api'
import { MESSAGE_LENGTH } from './ids'
import './messages'

/** How often the menu's badge asks again while the console is in front (as the app's inbox badge). */
const UNREAD_INTERVAL = 5 * 60_000

/**
 * The menu's badge (Q82, D-Q82-9): how many conversations have a taker's message the sponsor has not read. Asked on
 * entering, every five minutes and when the console comes back to the front; a failure shows none.
 */
export function useUnreadConversations(enabled = true): number {
  const unread = useQuery({ queryKey: [...CONVERSATIONS_QUERY, 'unread'], queryFn: readUnreadConversations,
    enabled, refetchInterval: UNREAD_INTERVAL, refetchIntervalInBackground: false, refetchOnWindowFocus: false,
    staleTime: 30_000 })
  const { refetch } = unread
  useEffect(() => {
    if (!enabled) return
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refetch()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [enabled, refetch])
  return unread.data ?? 0
}

/**
 * The takers' conversations (Q82): a `DataTable` (cards on a phone), the taker as the title and the quiz under it,
 * the state a badge; those waiting for a reply first. Only waiting ones when asked.
 */
export function ConversationsTab() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const [waiting, setWaiting] = useState(false)
  const list = useQuery({ queryKey: [...CONVERSATIONS_QUERY, 'list', waiting],
    queryFn: () => readConversations({ waiting }) })
  const columns = useMemo<ColumnDef<ConversationRow, unknown>[]>(() => [
    { id: 'taker', header: t('conversations.columns.taker'), enableSorting: false,
      meta: { card: 'title', className: 'max-w-72 whitespace-normal' },
      cell: ({ row }) => (
        <Link to={conversationPath(row.original.conversationId)} data-testid="conversation-open"
          className={cn('underline-offset-4 hover:underline', row.original.unread && 'font-semibold')}>
          {row.original.takerName ?? t('conversations.taker')}
        </Link>
      ) },
    { id: 'publication', header: t('conversations.columns.publication'), enableSorting: false,
      meta: { card: 'subtitle' }, cell: ({ row }) => row.original.publicationName },
    { id: 'state', header: t('conversations.columns.state'), enableSorting: false, meta: { card: 'badge' },
      cell: ({ row }) => row.original.status === 'CLOSED'
        ? <Badge variant="outline">{t('conversations.closed')}</Badge>
        : row.original.waitingReply
          ? <Badge variant={row.original.unread ? 'default' : 'secondary'} data-testid="conversation-waiting">
            {row.original.unread ? t('conversations.new') : t('conversations.waiting')}</Badge>
          : <Badge variant="secondary">{t('conversations.answered')}</Badge> },
    { id: 'last', header: t('conversations.columns.last'), enableSorting: false,
      meta: { className: 'max-w-80 truncate' }, cell: ({ row }) => row.original.lastBody ?? '—' },
    { id: 'when', header: t('conversations.columns.when'), enableSorting: false,
      cell: ({ row }) => formatDateTime(row.original.lastMessageAt) },
  ], [t])
  // Waiting for a reply first (the risk "unanswered", §8), then the newest.
  const rows = useMemo(() => [...(list.data ?? [])].sort((a, b) => Number(b.waitingReply) - Number(a.waitingReply)
    || b.lastMessageAt.localeCompare(a.lastMessageAt)), [list.data])
  return (
    <div className="flex flex-col gap-4" data-testid="conversations">
      <p className="text-muted-foreground text-sm">{t('conversations.intro')}</p>
      <div className="flex items-center gap-2">
        <Switch id={`${id}-waiting`} checked={waiting} onCheckedChange={setWaiting}
          data-testid="conversations-waiting" />
        <Label htmlFor={`${id}-waiting`}>{t('conversations.onlyWaiting')}</Label>
      </div>
      {list.isPending ? (
        <PageState kind="loading" />
      ) : list.isError ? (
        <PageState kind="error" description={problemText(list.error)}
          action={<Button variant="outline" onClick={() => void list.refetch()}>{tQb('retry')}</Button>} />
      ) : rows.length === 0 ? (
        <PageState kind="empty" title={t('conversations.empty')} data-testid="conversations-empty" />
      ) : (
        <DataTable label={t('conversations.columns.taker')} columns={columns} data={rows}
          storageKey="qb.sponsor.conversations" getRowId={(row) => row.conversationId}
          rowProps={(row) => ({ 'data-testid': 'conversation-row', 'data-id': row.conversationId,
            'data-unread': row.unread ? 'true' : 'false' })} />
      )}
    </div>
  )
}

const REWARD_PATHS = { CASH: CASH_REWARDS_PATH, REDEEM: REDEEM_PATH, DIGITAL: DIGITAL_PRIZES_PATH } as const

/**
 * One conversation (Q82): the messages, a box to answer while it is open, closing it, and beside them what the
 * taker won on this quiz that the sponsor settles (D-Q82-13), each leading to its page. Opening it marks it read.
 */
export function ConversationPage({ conversationId }: { conversationId: string }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const key = [...CONVERSATIONS_QUERY, 'one', conversationId]
  const conversation = useQuery({ queryKey: key,
    queryFn: async () => (await readConversations({ conversationId }))[0] ?? null })
  const messages = useQuery({ queryKey: [...key, 'messages'], queryFn: () => readMessages(conversationId) })
  const rewards = useQuery({ queryKey: [...key, 'rewards'], queryFn: () => readRewards(conversationId) })
  const refresh = () => queryClient.invalidateQueries({ queryKey: CONVERSATIONS_QUERY })
  const unread = conversation.data?.unread === true
  useEffect(() => {
    if (!unread) return
    void markRead(conversationId).then(() => queryClient.invalidateQueries({ queryKey: CONVERSATIONS_QUERY }),
      () => undefined)
  }, [unread, conversationId, queryClient])
  const close = useMutation({ mutationFn: () => closeConversation(conversationId), onSuccess: () => {
    notify.success(t('conversations.closedDone'))
    void refresh()
  }, onError: (error) => notify.error(problemText(error)) })
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
  const taker = data.takerName ?? t('conversations.taker')
  return (
    <section className="flex flex-col gap-4" aria-labelledby="conversation-title" data-testid="conversation">
      <div className="flex flex-col gap-1">
        <Link to={CONVERSATIONS_PATH} className="text-muted-foreground inline-flex w-fit items-center gap-1 text-sm">
          <ArrowLeft aria-hidden className="size-4" />
          {t('conversations.back')}
        </Link>
        <h1 id="conversation-title" className="text-xl font-semibold" data-testid="conversation-taker">{taker}</h1>
        <p className="text-muted-foreground text-sm">{data.publicationName}</p>
      </div>
      <div className="grid gap-4 lg:grid-cols-[1fr_18rem]">
        <div className="flex flex-col gap-4">
          <ol className="flex flex-col gap-2" aria-label={t('conversations.messages')}
            data-testid="conversation-messages">
            {messages.data.map((message) => {
              const mine = message.sender === 'SPONSOR'
              return (
                <li key={message.messageId} data-testid="conversation-message" data-sender={message.sender}
                  className={cn('flex max-w-[85%] flex-col gap-1 rounded-lg border p-3',
                    mine ? 'bg-accent self-end' : 'self-start')}>
                  <span className="text-muted-foreground text-xs">
                    {mine ? t('conversations.you') : taker}{' · '}{formatDateTime(message.createdAt)}
                  </span>
                  <span className="text-sm whitespace-pre-wrap break-words">
                    {message.body ?? <em className="text-muted-foreground">{t('conversations.erased')}</em>}
                  </span>
                </li>
              )
            })}
          </ol>
          {data.status === 'OPEN' ? (
            <>
              <Reply conversationId={conversationId} onSent={refresh} />
              <Button variant="outline" size="sm" className="self-start" disabled={close.isPending}
                onClick={() => close.mutate()} data-testid="conversation-close">{t('conversations.close')}</Button>
            </>
          ) : (
            <p className="text-muted-foreground rounded-lg border p-3 text-sm" data-testid="conversation-closed">
              {t(`conversations.closedBy.${data.closedBy ?? 'SPONSOR'}`)}
            </p>
          )}
        </div>
        <Card data-testid="conversation-rewards">
          <CardHeader><CardTitle className="text-base">{t('conversations.rewards.title')}</CardTitle></CardHeader>
          <CardContent className="flex flex-col gap-2 text-sm">
            {rewards.isPending ? <p className="text-muted-foreground">…</p>
              : rewards.isError ? <p className="text-destructive">{problemText(rewards.error)}</p>
                : rewards.data.length === 0 ? (
                  <p className="text-muted-foreground" data-testid="conversation-rewards-none">
                    {t('conversations.rewards.none')}</p>
                ) : rewards.data.map((reward) => (
                  <Link key={`${reward.kind}:${reward.recordId}`} to={REWARD_PATHS[reward.kind]}
                    data-testid="conversation-reward" className="hover:bg-accent flex flex-col rounded-md border p-2">
                    <span className="font-medium">{t(`conversations.rewards.kinds.${reward.kind}`)}
                      {' · '}{formatKudos(reward.amount)}</span>
                    <span className="text-muted-foreground text-xs">
                      {reward.code ?? (reward.hint ? `…${reward.hint}` : '')}{' · '}{reward.status}
                    </span>
                  </Link>
                ))}
          </CardContent>
        </Card>
      </div>
    </section>
  )
}

/** The answer box: the server checks the text (no addresses, so many a day) and says why not. */
function Reply({ conversationId, onSent }: { conversationId: string; onSent: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [body, setBody] = useState('')
  const send = useMutation({
    mutationFn: () => sendMessage(conversationId, body),
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
    <form className="flex flex-col gap-2" onSubmit={submit} data-testid="conversation-reply">
      <Label htmlFor={`${id}-body`}>{t('conversations.reply')}</Label>
      <Textarea id={`${id}-body`} value={body} maxLength={MESSAGE_LENGTH} rows={4}
        onChange={(event) => setBody(event.target.value)} data-testid="conversation-body" />
      <p className="text-muted-foreground text-xs">{t('conversations.plainText')}</p>
      {send.isError && (
        <p className="text-destructive text-sm" role="alert" data-testid="conversation-error">{problemText(send.error)}</p>
      )}
      <Button type="submit" className="self-end" disabled={!body.trim() || send.isPending}
        data-testid="conversation-send">{t('conversations.send')}</Button>
    </form>
  )
}
