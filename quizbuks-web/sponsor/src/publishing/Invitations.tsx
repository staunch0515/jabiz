import { formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  ConfirmDialog,
  DataTable,
  Input,
  Label,
  notify,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
  type ColumnDef,
  type PaginationState,
} from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Search, Send, Trash2, UserPlus } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { EmptyState } from '../EmptyState'
import { QB } from '../qb/messages'
import { formatKudos } from '../qb/kudos'
import { problemText } from '../qb/problems'
import {
  addInvitations,
  INVITATION_STATUSES,
  INVITATIONS_PER_CALL,
  readInvitationSummary,
  removeInvitation,
  searchInvitations,
  sendInvitations,
  splitAddresses,
  type InvitationRow,
  type InvitationStatus,
  type PublicationStatus,
} from './api'
import { useMailServer } from './MailServerPage'
import { MAIL_SERVER_PATH } from './paths'
import { hrefOf, navigate } from '../qb/router'
import { useInvitationStatusLabel } from './shared'

const PAGE_SIZE = 20
const STATUS_BADGES: Record<InvitationStatus, 'outline' | 'secondary' | 'warning' | 'success'> = {
  ADDED: 'outline',
  QUEUED: 'secondary',
  SENT: 'secondary',
  STARTED: 'warning',
  ANSWERED: 'success',
}

export const invitationsQuery = (publicationId: string) => ['qb', 'invitations', publicationId] as const

/**
 * The invitation list of an invited publication (Q25, templates `qb.sponsor.invitations` and
 * `qb.sponsor.invitation-summary`): the counts, a box to add addresses (`QB_INVITATION_ADD`), the button that queues
 * the mail of every address not yet written to while the publication is live (`QB_INVITATION_SEND`; the job sends them
 * within a minute), and the
 * rows with each address's mail and answer; an address nobody started can be removed (`QB_INVITATION_REMOVE`).
 */
export function InvitationsSection({ publicationId, status }: { publicationId: string; status: PublicationStatus }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const queryClient = useQueryClient()
  const statusLabel = useInvitationStatusLabel()
  const [addresses, setAddresses] = useState('')
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState<InvitationStatus | null>(null)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [confirmSend, setConfirmSend] = useState(false)
  const [removing, setRemoving] = useState<InvitationRow | null>(null)
  const key = invitationsQuery(publicationId)
  const refresh = () => queryClient.invalidateQueries({ queryKey: key })

  const summary = useQuery({ queryKey: [...key, 'summary'], queryFn: () => readInvitationSummary(publicationId) })
  const list = useQuery({
    queryKey: [...key, 'list', q, filter, pagination],
    queryFn: () => searchInvitations({ publicationId, q, status: filter, offset: pagination.pageIndex * pagination.pageSize,
      limit: pagination.pageSize }),
    placeholderData: keepPreviousData,
  })
  const emails = splitAddresses(addresses)
  const add = useMutation({
    mutationFn: () => addInvitations(publicationId, emails),
    onSuccess: async (output) => {
      notify.success(t('publishing.invitations.added', { added: output.added, skipped: output.skipped }))
      setAddresses('')
      await refresh()
    },
  })
  // The dialogs stay open while the call runs and when it is refused (the refusal is told by a toast).
  const send = useMutation({
    mutationFn: () => sendInvitations(publicationId),
    onSuccess: async (output) => {
      notify.success(t('publishing.invitations.sent', { queued: output.queued, remaining: output.remainingToday }))
      await refresh()
    },
    onError: (error) => notify.error(problemText(error)),
  })
  const remove = useMutation({
    mutationFn: (row: InvitationRow) => removeInvitation(row.invitationId),
    onSuccess: async (_output, row) => {
      notify.success(t('publishing.invitations.removed', { email: row.email }))
      await refresh()
    },
    onError: (error) => notify.error(problemText(error)),
  })
  // Invitations leave through the sponsor's own mail server (Q29): none verified, nothing can be sent.
  const mailServer = useMailServer()
  const serverReady = Boolean(mailServer.data?.verifiedAt)
  const sendable = status === 'LIVE' && serverReady

  const columns = useMemo<ColumnDef<InvitationRow, unknown>[]>(() => [
    { id: 'email', header: t('publishing.invitations.email'), enableSorting: false,
      cell: ({ row }) => <span className="break-all" data-testid="invitation-email">{row.original.email}</span> },
    { id: 'status', header: t('publishing.invitations.status'), enableSorting: false,
      cell: ({ row }) => (
        <Badge variant={STATUS_BADGES[row.original.status]} data-testid="invitation-status"
          data-status={row.original.status}>
          {statusLabel(row.original.status)}
        </Badge>
      ) },
    { id: 'mail', header: t('publishing.invitations.mail'), enableSorting: false,
      cell: ({ row }) => row.original.lastSentAt
        ? t('publishing.invitations.mailValue', { count: row.original.sentCount, time: formatDateTime(row.original.lastSentAt) })
        : <span className="text-muted-foreground">{t('publishing.invitations.notSent')}</span> },
    { id: 'answer', header: t('publishing.invitations.answer'), enableSorting: false,
      cell: ({ row }) => row.original.answeredAt
        ? <span data-testid="invitation-answer">{t('publishing.invitations.answeredValue', {
            time: formatDateTime(row.original.answeredAt), score: row.original.score ?? '?',
            reward: formatKudos(row.original.reward ?? '0') })}</span>
        : row.original.startedAt
          ? t('publishing.invitations.startedValue', { time: formatDateTime(row.original.startedAt) })
          : '' },
    { id: 'actions', header: '', enableSorting: false,
      cell: ({ row }) => row.original.status === 'STARTED' || row.original.status === 'ANSWERED' ? null : (
        <Button size="sm" variant="ghost" data-testid="invitation-remove"
          aria-label={t('publishing.invitations.removeTitle', { email: row.original.email })}
          onClick={() => setRemoving(row.original)}>
          <Trash2 aria-hidden />
          {t('publishing.invitations.remove')}
        </Button>
      ) },
  ], [t, statusLabel])

  const search = (event: FormEvent) => {
    event.preventDefault()
    setQ(text.trim())
    setPagination((p) => ({ ...p, pageIndex: 0 }))
  }

  return (
    <section className="flex flex-col gap-4" aria-labelledby={`${id}-title`} data-testid="invitations">
      <div className="flex flex-col gap-1">
        <h2 id={`${id}-title`} className="text-lg font-semibold">{t('publishing.invitations.title')}</h2>
        <p className="text-muted-foreground text-sm">{t('publishing.invitations.intro')}</p>
        {summary.data && (
          <p className="text-sm" data-testid="invitations-summary">{t('publishing.invitations.summary', { ...summary.data })}</p>
        )}
      </div>
      <form noValidate className="flex flex-col gap-2" onSubmit={(event) => {
        event.preventDefault()
        if (emails.length > 0 && !add.isPending) add.mutate()
      }}>
        <Label htmlFor={`${id}-addresses`}>{t('publishing.invitations.addresses')}</Label>
        <Textarea id={`${id}-addresses`} value={addresses} rows={3} data-testid="invitations-addresses"
          aria-describedby={`${id}-addresses-hint`} onChange={(event) => setAddresses(event.target.value)} />
        <p id={`${id}-addresses-hint`} className="text-muted-foreground text-sm">
          {t('publishing.invitations.addressesHint', { max: INVITATIONS_PER_CALL })}
        </p>
        {add.isError && <p className="text-destructive text-sm" role="alert">{problemText(add.error)}</p>}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" disabled={emails.length === 0 || emails.length > INVITATIONS_PER_CALL || add.isPending}
            aria-busy={add.isPending || undefined} data-testid="invitations-add">
            <UserPlus aria-hidden />
            {t('publishing.invitations.add')}
          </Button>
          <Button type="button" variant="outline" data-testid="invitations-send"
            disabled={!sendable || send.isPending || (summary.data?.added ?? 0) === 0}
            onClick={() => setConfirmSend(true)}>
            <Send aria-hidden />
            {t('publishing.invitations.send')}
          </Button>
        </div>
        {status !== 'LIVE' && <p className="text-muted-foreground text-sm">{t('publishing.invitations.notLive')}</p>}
        {status === 'LIVE' && mailServer.isSuccess && !serverReady && (
          <p className="text-sm" data-testid="invitations-needs-server">
            {t('publishing.invitations.needsServer')}{' '}
            <a href={hrefOf(MAIL_SERVER_PATH)} className="text-primary underline" onClick={(event) => {
              event.preventDefault()
              navigate(MAIL_SERVER_PATH)
            }}>
              {t('publishing.invitations.setUpServer')}
            </a>
          </p>
        )}
      </form>
      <div className="flex flex-wrap items-end gap-2">
        <form role="search" onSubmit={search} className="flex gap-2">
          <Label htmlFor={`${id}-q`} className="sr-only">{t('publishing.invitations.search')}</Label>
          <Input id={`${id}-q`} type="search" value={text} maxLength={100} placeholder={t('publishing.invitations.search')}
            data-testid="invitations-search" onChange={(event) => setText(event.target.value)} />
          <Button type="submit" variant="outline" size="icon" aria-label={t('publishing.invitations.search')}>
            <Search aria-hidden />
          </Button>
        </form>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-status`} className="sr-only">{t('publishing.invitations.status')}</Label>
          <Select value={filter ?? 'ALL'} onValueChange={(value) => {
            setFilter(value === 'ALL' ? null : (value as InvitationStatus))
            setPagination((p) => ({ ...p, pageIndex: 0 }))
          }}>
            <SelectTrigger id={`${id}-status`} className="w-48" data-testid="invitations-status-filter">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">{t('publishing.invitations.allStatuses')}</SelectItem>
              {INVITATION_STATUSES.map((code) => <SelectItem key={code} value={code}>{statusLabel(code)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      </div>
      {list.isError ? (
        <PageState kind="error" description={problemText(list.error)}
          action={<Button variant="outline" onClick={() => void list.refetch()}>{tQb('retry')}</Button>} />
      ) : (
        <DataTable label={t('publishing.invitations.title')} columns={columns} data={list.data?.items ?? []}
          getRowId={(row) => row.invitationId} loading={list.isPending} stale={list.isPlaceholderData}
          empty={q || filter ? t('publishing.invitations.noMatch')
            : <EmptyState testId="invitations-empty">{t('publishing.invitations.none')}</EmptyState>}
          pagination={pagination} onPaginationChange={setPagination} rowCount={list.data?.total}
          rowProps={(row) => ({ 'data-testid': 'invitation-row', 'data-status': row.status })} />
      )}
      <ConfirmDialog open={confirmSend} title={t('publishing.invitations.sendTitle')}
        description={t('publishing.invitations.sendText')} confirmLabel={t('publishing.invitations.send')}
        cancelLabel={t('common.cancel')} onConfirm={() => send.mutateAsync()} onOpenChange={setConfirmSend} />
      <ConfirmDialog open={removing !== null}
        title={t('publishing.invitations.removeTitle', { email: removing?.email ?? '' })}
        description={t('publishing.invitations.removeText')} confirmLabel={t('publishing.invitations.remove')}
        cancelLabel={t('common.cancel')} destructive
        onConfirm={() => (removing ? remove.mutateAsync(removing) : undefined)}
        onOpenChange={(open) => { if (!open) setRemoving(null) }} />
    </section>
  )
}
