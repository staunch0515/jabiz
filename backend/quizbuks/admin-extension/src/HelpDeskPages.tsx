import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  ConfirmDialog,
  EXTENSION_NAMESPACE,
  Label,
  PageHeader,
  PageState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Textarea,
  UserName,
} from '@jabiz/admin'
import { EmptyState, LoadFailed } from './components'
import {
  changedMeanwhile,
  readStaffMessages,
  readStaffThread,
  readStaffThreads,
  replyHelp,
  type StaffMessage,
  type StaffThread,
  type ThreadFilter,
} from './help-api'
import { when } from './hooks'
import { PATH } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

/** The longest reply the server takes (`QbHelp.TEXT_LENGTH`). */
const REPLY_MAX = 4000

const QUERY = ['qb-ext', 'help'] as const

/** Who asked: the display name (a Taker's) or the company (a Sponsor's), and the platform's user. */
function Asker({ thread }: { thread: StaffThread }) {
  return (
    <span className="flex flex-col">
      {thread.name && <span>{thread.name}</span>}
      <span className="text-muted-foreground text-xs"><UserName id={thread.userId} /></span>
    </span>
  )
}

function StatusBadge({ thread }: { thread: StaffThread }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <Badge variant={thread.status === 'CLOSED' ? 'outline' : thread.waitingFor === 'STAFF' ? 'warning' : 'secondary'}
      data-testid="help-thread-status" data-status={thread.status}>
      {t(`help.statuses.${thread.status}`, { defaultValue: thread.status })}
      {thread.status === 'CLOSED' && thread.closeReason
        ? ` (${t(`help.closeReasons.${thread.closeReason}`, { defaultValue: thread.closeReason })})` : ''}
    </Badge>
  )
}

/**
 * The help desk (F-C10c; `qb.admin.help`): the AI help conversations handed to a person, for headquarters (every one:
 * Takers' and direct Sponsors' are theirs to answer) and an operator's members (their own Sponsors' only, as the
 * server scopes them). Those waiting for a reply first is the filter "waiting for us".
 */
export function HelpThreadsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [filter, setFilter] = useState<ThreadFilter>('waiting')
  const threads = useQuery({ queryKey: [...QUERY, 'threads', filter], queryFn: () => readStaffThreads(filter) })

  let body: ReactNode
  if (threads.isError) body = <LoadFailed error={threads.error} onRetry={() => void threads.refetch()} />
  else if (threads.isPending) body = <PageState kind="loading" />
  else if (threads.data.length === 0) body = <EmptyState title={t('help.none')} testId="help-threads-none" />
  else {
    body = (
      <div className="overflow-x-auto [&_[data-slot=table-container]]:overflow-visible" tabIndex={0} role="region" aria-label={t('help.table')}>
        <Table data-testid="help-threads">
          <TableHeader>
            <TableRow>
              <TableHead>{t('help.columns.user')}</TableHead>
              <TableHead>{t('help.columns.entry')}</TableHead>
              <TableHead>{t('help.columns.status')}</TableHead>
              <TableHead>{t('help.columns.waiting')}</TableHead>
              <TableHead>{t('help.columns.operator')}</TableHead>
              <TableHead>{t('help.columns.last')}</TableHead>
              <TableHead><span className="sr-only">{t('help.open')}</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {threads.data.map((thread) => (
              <TableRow key={thread.threadId} data-testid="help-thread-row" data-thread={thread.threadId}>
                <TableCell><Asker thread={thread} /></TableCell>
                <TableCell>{t(`help.entries.${thread.entry}`, { defaultValue: thread.entry })}</TableCell>
                <TableCell><StatusBadge thread={thread} /></TableCell>
                <TableCell className="whitespace-nowrap">
                  {thread.status !== 'CLOSED' && thread.waitingFor
                    ? <>{t(`help.waitingFor.${thread.waitingFor}`, { defaultValue: thread.waitingFor })}
                      <span className="text-muted-foreground block text-xs">{when(thread.waitingSince)}</span></>
                    : ''}
                </TableCell>
                <TableCell>{thread.operatorName ?? t('help.headquarters')}</TableCell>
                <TableCell className="whitespace-nowrap">{when(thread.lastActivityAt)}</TableCell>
                <TableCell>
                  <Link to={PATH.helpThread(thread.threadId)} className={linkClass} data-testid="help-thread-open">
                    {t('help.open')}</Link>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4" data-testid="qb-help">
      <PageHeader title={t('help.title')} description={t('help.description')} className="pb-0" />
      <div role="group" aria-label={t('help.filter')} className="flex flex-wrap gap-2">
        {(['waiting', 'open', 'all'] as const).map((value) => (
          <Button key={value} type="button" size="sm" variant={filter === value ? 'default' : 'outline'}
            aria-pressed={filter === value} data-testid={`help-filter-${value}`} onClick={() => setFilter(value)}>
            {t(`help.filters.${value}`)}
          </Button>
        ))}
      </div>
      {body}
    </div>
  )
}

function MessageRow({ message }: { message: StaffMessage }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const mine = message.kind === 'STAFF'
  return (
    <li className={`flex flex-col gap-1 rounded-lg border p-3 ${mine ? 'bg-muted ml-8' : 'bg-card mr-8'}`}
      data-testid="help-message" data-kind={message.kind}>
      <span className="text-muted-foreground flex flex-wrap gap-2 text-xs">
        <span className="font-medium">{t(`help.thread.kinds.${message.kind}`, { defaultValue: message.kind })}</span>
        <span>{when(message.createdAt)}</span>
        {message.page && <span>{t('help.thread.page', { page: message.page })}</span>}
        {message.kind === 'QUESTION' && message.jobStatus && message.jobStatus !== 'ANSWERED' && (
          <span>{t(`help.thread.jobs.${message.jobStatus}`, { defaultValue: message.jobStatus })}</span>
        )}
        {message.rating && <span>{t(`help.thread.rated.${message.rating}`, { defaultValue: message.rating })}</span>}
      </span>
      {/* Plain text: what the user, the AI or a colleague wrote is never rendered as markup. */}
      <p className="text-sm break-words whitespace-pre-wrap" data-testid="help-message-body">
        {message.body ?? t('help.thread.erased')}</p>
      {message.links && (
        <p className="text-muted-foreground text-xs break-all whitespace-pre-wrap">{message.links}</p>
      )}
    </li>
  )
}

/**
 * One conversation handed to a person (F-C10c; the task's link `/qb/help/<id>`): who asked, everything said, and the
 * reply: reply, reply and close, or close. A 409 (someone else wrote meanwhile) reads the conversation again and keeps
 * the reply typed.
 */
export function HelpThreadPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { threadId = '' } = useParams()
  const id = useId()
  const client = useQueryClient()
  const [text, setText] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const thread = useQuery({ queryKey: [...QUERY, 'thread', threadId], queryFn: () => readStaffThread(threadId) })
  const messages = useQuery({ queryKey: [...QUERY, 'messages', threadId],
    queryFn: () => readStaffMessages(threadId) })
  const reload = () => {
    void client.invalidateQueries({ queryKey: QUERY })
  }
  const reply = useMutation({
    mutationFn: ({ close, withText }: { close: boolean; withText: boolean }) =>
      replyHelp(threadId, withText ? text.trim() : null, close),
    onSuccess: (_, { withText }) => {
      if (withText) setText('')
      setProblem(null)
      setDone(withText ? t('help.thread.sent') : t('help.thread.closed'))
      reload()
    },
    onError: (error) => {
      setDone(null)
      if (changedMeanwhile(error)) {
        setProblem(t('help.thread.conflict'))
        reload()
      } else {
        setProblem(error instanceof ApiError ? error.display : t('common.failed'))
      }
    },
  })
  const send = (close: boolean) => {
    if (!text.trim()) {
      setProblem(t('help.thread.textRequired'))
      return
    }
    reply.mutate({ close, withText: true })
  }

  const back = (
    <Link to={PATH.help} className="text-muted-foreground inline-flex w-fit items-center gap-1 text-sm">
      <ArrowLeft aria-hidden className="size-4" />
      {t('help.back')}
    </Link>
  )
  if (thread.isError || messages.isError) {
    return <div className="flex flex-col gap-4">{back}<LoadFailed error={thread.error ?? messages.error}
      onRetry={reload} /></div>
  }
  if (thread.isPending || messages.isPending) return <div className="flex flex-col gap-4">{back}<PageState kind="loading" /></div>
  const data = thread.data
  if (!data) {
    return <div className="flex flex-col gap-4">{back}
      <EmptyState title={t('help.thread.notFound')} testId="help-thread-none" /></div>
  }
  const open = data.status === 'ESCALATED'
  return (
    <section className="flex flex-col gap-4" data-testid="help-thread" data-thread={data.threadId}>
      {back}
      <PageHeader title={t('help.thread.title')} className="pb-0" />
      <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted-foreground">{t('help.thread.user')}</dt>
        <dd data-testid="help-thread-user"><Asker thread={data} /></dd>
        <dt className="text-muted-foreground">{t('help.columns.entry')}</dt>
        <dd>{t(`help.entries.${data.entry}`, { defaultValue: data.entry })}</dd>
        <dt className="text-muted-foreground">{t('help.thread.language')}</dt>
        <dd>{data.language ?? ''}</dd>
        <dt className="text-muted-foreground">{t('help.columns.status')}</dt>
        <dd><StatusBadge thread={data} /></dd>
        <dt className="text-muted-foreground">{t('help.columns.operator')}</dt>
        <dd>{data.operatorName ?? t('help.headquarters')}</dd>
        <dt className="text-muted-foreground">{t('help.thread.escalatedAt')}</dt>
        <dd>{when(data.escalatedAt)}</dd>
      </dl>
      <ol className="flex flex-col gap-2" aria-label={t('help.thread.messages')} data-testid="help-messages">
        {messages.data.map((message) => <MessageRow key={message.messageId} message={message} />)}
      </ol>
      {done && <p role="status" className="text-sm" data-testid="help-reply-done">{done}</p>}
      {problem && (
        <Alert variant="destructive" data-testid="help-reply-problem">
          <AlertDescription>{problem}</AlertDescription>
        </Alert>
      )}
      {open ? (
        <form noValidate className="flex flex-col gap-2" data-testid="help-reply-form" onSubmit={(event) => {
          event.preventDefault()
          send(false)
        }}>
          <Label htmlFor={`${id}-reply`}>{t('help.thread.reply')}</Label>
          <Textarea id={`${id}-reply`} value={text} rows={5} maxLength={REPLY_MAX} aria-describedby={`${id}-hint`}
            data-testid="help-reply-text" onChange={(event) => setText(event.target.value)} />
          <p id={`${id}-hint`} className="text-muted-foreground text-xs">{t('help.thread.replyHint')}</p>
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={reply.isPending} aria-busy={reply.isPending || undefined}
              data-testid="help-reply-send">{t('help.thread.send')}</Button>
            <Button type="button" variant="outline" disabled={reply.isPending} data-testid="help-reply-close"
              onClick={() => send(true)}>{t('help.thread.sendClose')}</Button>
            <ConfirmDialog title={t('help.thread.closeTitle')} description={t('help.thread.closeText')}
              confirmLabel={t('help.thread.closeConfirm')} cancelLabel={t('help.thread.cancel')}
              onConfirm={() => reply.mutateAsync({ close: true, withText: false }).catch(() => undefined)}
              trigger={<Button type="button" variant="ghost" disabled={reply.isPending} data-testid="help-close-only">
                {t('help.thread.close')}</Button>} />
          </div>
        </form>
      ) : (
        <p className="text-muted-foreground text-sm" data-testid="help-thread-closed">{t('help.thread.closed')}</p>
      )}
    </section>
  )
}
