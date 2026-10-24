import { formatDateTime, i18n } from '@jabiz/client'
import {
  Badge,
  Button,
  ConfirmDialog,
  MarkdownView,
  PageHeader,
  PageState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  notify,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { pickText } from '../qb/legal'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { CASH_REWARDS_PATH } from '../publishing/CashRewardsPage'
import { REDEEM_PATH } from '../publishing/RedeemPage'
import { useRegionLabel } from '../publishing/shared'
import { Link } from '../qb/router'
import {
  ANNOUNCEMENTS_QUERY,
  cancelNotice,
  markAnnouncementRead,
  NOTICES_QUERY,
  rate,
  readAnnouncements,
  readNotices,
  readNoticeStatuses,
  withdrawNotice,
  type Announcement,
  type NoticeRow,
  type NoticeStatus,
} from './api'
import { NoticeEditor } from './NoticeEditor'
import './messages'

const VARIANT: Record<NoticeStatus, 'outline' | 'secondary' | 'success' | 'warning'> = {
  DRAFT: 'outline',
  SCHEDULED: 'secondary',
  SENT: 'success',
  CANCELLED: 'outline',
  WITHDRAWN: 'warning',
}

function useStatusLabel() {
  const items = useQuery({ queryKey: ['qb', 'dict', 'broadcast-status'], queryFn: readNoticeStatuses,
    staleTime: Infinity })
  return (code: string) => items.data?.find((item) => item.code === code)?.label ?? code
}

/**
 * A count of the statistics with its rate, as of the day's count; "not counted yet" before it, "fewer than 5" when
 * the server does not tell (M-61, Q7-14; review M2).
 */
function Stat({ row, value, base, testId }: { row: NoticeRow; value: number | null; base: number | null;
  testId: string }) {
  const { t } = useTranslation(SPONSOR)
  if (row.status !== 'SENT' && row.status !== 'WITHDRAWN') return <span aria-hidden>–</span>
  if (row.snapshotAt == null) return <span data-testid={testId}>{t('notices.notCounted')}</span>
  if (value == null) return <span data-testid={testId}>{t('notices.fewer')}</span>
  const share = rate(value, base)
  return (
    <span data-testid={testId}>
      {share == null ? t('notices.statCount', { count: value }) : t('notices.stat', { count: value, rate: share })}
    </span>
  )
}

/** What became of a notice that was not sent or was taken back. */
function Fate({ row }: { row: NoticeRow }) {
  const { t } = useTranslation(SPONSOR)
  if (row.status === 'CANCELLED' && row.cancelReason) {
    return <p className="text-muted-foreground text-xs">{t(`notices.cancelled.${row.cancelReason}`,
      { defaultValue: row.cancelReason })}</p>
  }
  if (row.status !== 'WITHDRAWN') return null
  const text = row.withdrawReason === 'QB_ACCOUNT_SUSPENDED' ? t('notices.withdrawnBan')
    : row.withdrawnByAdmin ? t('notices.withdrawnAdmin', { reason: row.withdrawReason ?? '' })
    : t('notices.withdrawnSelf')
  return <p className="text-warning text-xs" data-testid="notice-withdrawn">{text}</p>
}

function Actions({ row, onEdit }: { row: NoticeRow; onEdit: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const act = (run: () => Promise<unknown>) => run().then(() => {
    notify.success(t('notices.actions.done'))
    void queryClient.invalidateQueries({ queryKey: NOTICES_QUERY })
  }).catch((error: unknown) => {
    notify.error(problemText(error))
    throw error
  })
  return (
    <div className="flex flex-wrap gap-1">
      {row.status === 'DRAFT' && (
        <>
          <Button size="sm" variant="outline" onClick={onEdit} data-testid="notice-edit">
            {t('notices.actions.edit')}
          </Button>
          <ConfirmDialog title={t('notices.actions.discard')} description={t('notices.actions.discardText')}
            confirmLabel={t('notices.actions.confirm')} cancelLabel={t('notices.actions.cancel')} destructive
            onConfirm={() => act(() => cancelNotice(row.broadcastId, row.revision))}
            trigger={<Button size="sm" variant="ghost" data-testid="notice-discard">{t('notices.actions.discard')}</Button>} />
        </>
      )}
      {row.status === 'SCHEDULED' && (
        <ConfirmDialog title={t('notices.actions.unschedule')} description={t('notices.actions.unscheduleText')}
          confirmLabel={t('notices.actions.confirm')} cancelLabel={t('notices.actions.cancel')}
          onConfirm={() => act(() => cancelNotice(row.broadcastId, row.revision))}
          trigger={<Button size="sm" variant="outline" data-testid="notice-unschedule">
            {t('notices.actions.unschedule')}</Button>} />
      )}
      {(row.status === 'SENT' || row.status === 'SCHEDULED') && (
        <ConfirmDialog title={t('notices.actions.withdraw')} description={t('notices.actions.withdrawText')}
          confirmLabel={t('notices.actions.confirm')} cancelLabel={t('notices.actions.cancel')} destructive
          onConfirm={() => act(() => withdrawNotice(row.broadcastId, row.revision))}
          trigger={<Button size="sm" variant="ghost" data-testid="notice-withdraw">{t('notices.actions.withdraw')}</Button>} />
      )}
    </div>
  )
}

function Notices() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const status = useStatusLabel()
  const regionLabel = useRegionLabel()
  const notices = useQuery({ queryKey: [...NOTICES_QUERY, 'list'], queryFn: readNotices })
  // The latest of the day's counts (all notices are counted by one run).
  const counted = (notices.data ?? []).map((row) => row.snapshotAt).filter((at): at is string => !!at).sort().at(-1)
  const [editing, setEditing] = useState<NoticeRow | 'new' | null>(null)
  return (
    <div className="flex flex-col gap-4">
      {editing ? (
        <NoticeEditor key={editing === 'new' ? 'new' : editing.broadcastId}
          row={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />
      ) : (
        <div>
          <Button onClick={() => setEditing('new')} data-testid="notice-new">
            <Plus aria-hidden />
            {t('notices.new')}
          </Button>
        </div>
      )}
      {notices.isPending ? (
        <PageState kind="loading" />
      ) : notices.isError ? (
        <PageState kind="error" description={problemText(notices.error)}
          action={<Button variant="outline" onClick={() => void notices.refetch()}>{tQb('retry')}</Button>} />
      ) : notices.data.length === 0 ? (
        <PageState kind="empty" title={t('notices.empty')} data-testid="notices-empty" />
      ) : (
        <>
          <Table data-testid="notices-table">
            <TableHeader>
              <TableRow>
                <TableHead>{t('notices.columns.notice')}</TableHead>
                <TableHead>{t('notices.columns.status')}</TableHead>
                <TableHead>{t('notices.columns.when')}</TableHead>
                <TableHead className="text-right">{t('notices.columns.reached')}</TableHead>
                <TableHead className="text-right">{t('notices.columns.reads')}</TableHead>
                <TableHead className="text-right">{t('notices.columns.clicks')}</TableHead>
                <TableHead className="text-right">{t('notices.columns.conversions')}</TableHead>
                <TableHead>{t('notices.columns.actions')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {notices.data.map((row) => {
                const title = pickText(row.title, i18n.language)
                const when = row.sentAt ?? row.scheduledAt
                return (
                  <TableRow key={row.broadcastId} data-testid="notice-row" data-status={row.status}
                    data-id={row.broadcastId}>
                    <TableCell className="max-w-72 whitespace-normal">
                      <p className="font-medium" lang={title.lang}>{title.text || '—'}</p>
                      <p className="text-muted-foreground text-xs">
                        {row.publicationName ?? ''}{row.regions.length > 0
                          ? ` · ${row.regions.map(regionLabel).join(', ')}` : ''}
                      </p>
                      <Fate row={row} />
                    </TableCell>
                    <TableCell><Badge variant={VARIANT[row.status]}>{status(row.status)}</Badge></TableCell>
                    <TableCell>{when ? formatDateTime(when) : '—'}</TableCell>
                    <TableCell className="text-right" data-testid="notice-reached">
                      {row.status !== 'SENT' && row.status !== 'WITHDRAWN' ? '–'
                        : row.audienceCount == null ? t('notices.fewerReach')
                          : t('notices.reach', { count: row.audienceCount })}
                    </TableCell>
                    <TableCell className="text-right">
                      <Stat row={row} value={row.reads} base={row.audienceCount} testId="notice-reads" />
                    </TableCell>
                    <TableCell className="text-right">
                      <Stat row={row} value={row.clicks} base={row.reads} testId="notice-clicks" />
                    </TableCell>
                    <TableCell className="text-right">
                      <Stat row={row} value={row.conversions} base={row.clicks} testId="notice-conversions" />
                    </TableCell>
                    <TableCell><Actions row={row} onEdit={() => setEditing(row)} /></TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
          <p className="text-muted-foreground text-xs" data-testid="notices-stats-hint">
            {t('notices.statsHint')}
            {counted && <> {t('notices.countedAt', { time: formatDateTime(counted) })}</>}
          </p>
        </>
      )}
    </div>
  )
}

function AnnouncementItem({ item }: { item: Announcement }) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const read = useMutation({
    mutationFn: () => markAnnouncementRead(item.itemId, item.itemKind),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ANNOUNCEMENTS_QUERY }),
  })
  const title = pickText(item.title, i18n.language)
  const body = pickText(item.body, i18n.language)
  const panel = `announcement-${item.itemId}`
  return (
    <li className="rounded-lg border p-3" data-testid="announcement" data-unread={item.unread} data-kind={item.itemKind}>
      <div className="flex flex-wrap items-center gap-2">
        <span className={item.unread ? 'font-semibold' : 'font-medium'} lang={title.lang}>{title.text}</span>
        {item.unread && <Badge>{t('notices.inbox.unread')}</Badge>}
        <span className="text-muted-foreground text-xs">{formatDateTime(item.at)}</span>
        <Button size="sm" variant="outline" className="ml-auto" aria-expanded={open} aria-controls={panel}
          data-testid="announcement-open" onClick={() => {
            setOpen(!open)
            if (!open && !item.readAt && !read.isPending) read.mutate()
          }}>
          {open ? t('notices.inbox.close') : t('notices.inbox.open')}
        </Button>
      </div>
      {open && (
        <div id={panel} className="mt-2 flex flex-col gap-1">
          {item.markdown ? <MarkdownView text={body.text} lang={body.lang} />
            : <p className="whitespace-pre-line text-sm" lang={body.lang}>{body.text}</p>}
          {item.action && (
            <Link to={item.action === 'CASH' ? CASH_REWARDS_PATH : REDEEM_PATH}
              className="text-primary w-fit text-sm underline underline-offset-4" data-testid="announcement-action">
              {t(item.action === 'CASH' ? 'notices.inbox.openCash' : 'notices.inbox.openRedeem')}
            </Link>
          )}
          {item.expiresAt && (
            <p className="text-muted-foreground text-xs">{t('notices.inbox.until', {
              time: formatDateTime(item.expiresAt) })}</p>
          )}
        </div>
      )}
    </li>
  )
}

function Announcements() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const items = useQuery({ queryKey: ANNOUNCEMENTS_QUERY, queryFn: readAnnouncements })
  if (items.isPending) return <PageState kind="loading" />
  if (items.isError) {
    return <PageState kind="error" description={problemText(items.error)}
      action={<Button variant="outline" onClick={() => void items.refetch()}>{tQb('retry')}</Button>} />
  }
  if (items.data.length === 0) return <PageState kind="empty" title={t('notices.inbox.empty')} />
  return (
    <ul className="flex flex-col gap-2" data-testid="announcements">
      {items.data.map((item) => <AnnouncementItem key={item.itemId} item={item} />)}
    </ul>
  )
}

/**
 * The message centre (M-60, M-61; Q7c): the sponsor's notices to the takers of their publications, with their state
 * and statistics, written in an editor that shows how they look in the app; and QuizBuks's announcements to sponsors.
 */
export function MessagesPage() {
  const { t } = useTranslation(SPONSOR)
  return (
    <section className="flex flex-col gap-4" data-testid="messages-page">
      <PageHeader title={t('notices.title')} description={t('notices.intro')} />
      <Tabs defaultValue="mine">
        <TabsList aria-label={t('notices.tabs.label')}>
          <TabsTrigger value="mine" data-testid="messages-tab-mine">{t('notices.tabs.mine')}</TabsTrigger>
          <TabsTrigger value="inbox" data-testid="messages-tab-inbox">{t('notices.tabs.inbox')}</TabsTrigger>
        </TabsList>
        <TabsContent value="mine" className="pt-2"><Notices /></TabsContent>
        <TabsContent value="inbox" className="pt-2"><Announcements /></TabsContent>
      </Tabs>
    </section>
  )
}
