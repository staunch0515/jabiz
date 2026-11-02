import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import { EXTENSION_NAMESPACE, PageHeader, UserName } from '@jabiz/admin'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { QUEUE, when } from './hooks'
import { PATH } from './ids'
import { readConversation, readConversationMessages } from './report-api'

/**
 * One conversation between a taker and a sponsor, read only (Q82, D-Q82-10; docs/quizbuks/plans/Q82-conversations.md):
 * opened from a report of it. Both sides and every writer are shown as the platform's users (`UserName`); the text is
 * plain, never rendered as markup. An operator's session reads its own sponsors' only (the server limits the datasets).
 */
export default function ConversationPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { conversationId = '' } = useParams()
  const conversation = useQuery({ queryKey: [...QUEUE.abuseReports, 'conversation', conversationId],
    queryFn: () => readConversation(conversationId) })
  const messages = useQuery({ queryKey: [...QUEUE.abuseReports, 'conversation', conversationId, 'messages'],
    queryFn: () => readConversationMessages(conversationId) })
  const back = (
    <Link to={PATH.abuseReports} className="text-muted-foreground inline-flex w-fit items-center gap-1 text-sm">
      <ArrowLeft aria-hidden className="size-4" />
      {t('abuseReports.title')}
    </Link>
  )
  if (conversation.isError || messages.isError) {
    return <div className="flex flex-col gap-4">{back}<LoadFailed error={conversation.error ?? messages.error}
      onRetry={() => { void conversation.refetch(); void messages.refetch() }} /></div>
  }
  if (conversation.isPending || messages.isPending) return <div className="flex flex-col gap-4">{back}</div>
  const data = conversation.data
  if (!data) {
    return <div className="flex flex-col gap-4">{back}
      <EmptyState title={t('abuseReports.conversation.none')} testId="conversation-none" /></div>
  }
  return (
    <section className="flex flex-col gap-4" data-testid="conversation">
      {back}
      <PageHeader title={t('abuseReports.conversation.title')} />
      <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted-foreground">{t('abuseReports.conversation.taker')}</dt>
        <dd data-testid="conversation-taker"><UserName id={data.takerId} /></dd>
        <dt className="text-muted-foreground">{t('abuseReports.conversation.sponsor')}</dt>
        <dd data-testid="conversation-sponsor"><UserName id={data.publisherId} /></dd>
        <dt className="text-muted-foreground">{t('abuseReports.conversation.quiz')}</dt>
        <dd><Link to={PATH.publication(data.publicationId)} className="text-primary underline-offset-4 hover:underline">
          {data.publicationId}</Link></dd>
        <dt className="text-muted-foreground">{t('abuseReports.conversation.status')}</dt>
        <dd><StatusBadge code={data.status} label={t(`abuseReports.conversation.statuses.${data.status}`,
          { defaultValue: data.status })} /></dd>
      </dl>
      <ol className="flex flex-col gap-2" aria-label={t('abuseReports.conversation.messages')}
        data-testid="conversation-messages">
        {messages.data.map((message) => (
          <li key={message.messageId} className="bg-card flex flex-col gap-1 rounded-lg border p-3"
            data-testid="conversation-message" data-sender={message.sender}>
            <span className="text-muted-foreground flex flex-wrap gap-2 text-xs">
              <span className="font-medium">{t(`abuseReports.conversation.sides.${message.sender}`,
                { defaultValue: message.sender })}</span>
              <UserName id={message.senderUserId} />
              <span>{when(message.createdAt)}</span>
            </span>
            {/* Plain text: what either side wrote is never rendered as markup. */}
            <span className="text-sm whitespace-pre-wrap break-words">
              {message.body ?? t('abuseReports.conversation.erased')}</span>
          </li>
        ))}
      </ol>
    </section>
  )
}
