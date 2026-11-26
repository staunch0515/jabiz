import { formatDateTime, i18n } from '@jabiz/client'
import { AreaHeader } from '../frame/AreaHeader'
import '../frame/messages'
import {
  Button,
  Checkbox,
  Input,
  Label,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  Spinner,
  Switch,
  cn,
} from '@jabiz/ui'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, CheckCheck, Clock, Gift, ListFilter, Search, Settings, Sparkles, Store, type LucideIcon } from 'lucide-react'
import { useEffect, useId, useState, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { BRAND } from '../qb/brand'
import { pickText } from '../qb/legal'
import { shownLanguages } from '../qb/api'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { useSoundSetting } from '../qb/SoundSettings'
import {
  INBOX_QUERY,
  STATE_QUERY,
  UNREAD_QUERY,
  itemPath,
  readAll,
  readInbox,
  readInboxState,
  saveInboxSettings,
  type Category,
  type InboxFilter,
  type InboxItem,
} from './api'
import './messages'
import { useMySponsor } from '../mysponsor/api'
import { accountPath } from '../pages/accountPaths'
import { SponsorPicture } from '../qb/view/SponsorPicture'
import { PostAgeText } from '../qb/view/FeedCard'

/** Items per page of the inbox. */
export const INBOX_PAGE = 20

const CATEGORIES: (Category | 'all')[] = ['all', 'SYSTEM', 'PROMO', 'REWARD', 'REMINDER']
/** Q68: a sponsor also has their own messages' category. */
const SPONSOR_CATEGORIES: (Category | 'all')[] = [...CATEGORIES, 'SPONSOR']

/** Who an item is from: QuizBuks for messages and announcements, the sponsor's company for a notice. */
export function senderOf(item: InboxItem, t: (key: string, options?: Record<string, unknown>) => string): string {
  if (item.sender !== 'SPONSOR') return t('inbox.fromQuizbuks')
  return item.sponsorName ? t('inbox.fromSponsor', { name: item.sponsorName }) : t('inbox.fromSponsorUnknown')
}

/** The kind's icon in the row's first column (Q113d-1, the prototype's): rewards in the reward's gold. */
const ICONS: Record<Category, { icon: LucideIcon; tone: string }> = {
  REWARD: { icon: Gift, tone: 'text-qb-reward-foreground' },
  PROMO: { icon: Sparkles, tone: 'text-primary' },
  SYSTEM: { icon: Bell, tone: 'text-muted-foreground' },
  REMINDER: { icon: Clock, tone: 'text-muted-foreground' },
  SPONSOR: { icon: Store, tone: 'text-muted-foreground' },
}

/** The name a row is from: the sponsor's company for a notice, the product's for the rest. */
function senderName(item: InboxItem, t: (key: string) => string): string {
  if (item.sender === 'SPONSOR') return item.sponsorName ?? t('inbox.fromSponsorUnknown')
  return BRAND.names[i18n.language as 'en' | 'zh' | 'ja'] ?? BRAND.names.en
}

/**
 * One notification (Q113d-1, as x.com's): the kind's icon in a column of its own (40 px), then the sender's picture
 * (32 px), "sender · time", the title and the beginning of the text; an unread one on the accent's tint, with its
 * word for screen readers.
 */
function Row({ item, at }: { item: InboxItem; at: string }) {
  const { t } = useTranslation(APP)
  const title = pickText(item.title, i18n.language)
  const body = pickText(item.body, i18n.language)
  const { icon: Icon, tone } = ICONS[item.category] ?? ICONS.SYSTEM
  const from = senderName(item, t)
  return (
    <li data-testid="inbox-row" data-kind={item.itemKind} data-id={item.itemId} data-unread={item.unread}
      className="border-border border-b">
      <Link to={itemPath(item.itemKind, item.itemId)}
        className={cn('hover:bg-accent/60 focus-visible:ring-ring/50 flex gap-2 px-4 py-3 outline-none focus-visible:ring-[3px] focus-visible:ring-inset',
          item.unread && 'bg-accent/40')}>
        <span className="flex w-10 shrink-0 justify-end" data-testid="inbox-icon" data-category={item.category}>
          <Icon aria-hidden className={cn('size-[30px]', tone)} strokeWidth={1.6} />
        </span>
        <span className="flex min-w-0 flex-1 flex-col">
          <SponsorPicture avatar={item.sponsorAvatar} name={from} size={32} />
          <span className="mt-3 flex min-w-0 gap-1">
            <span className="truncate font-bold" title={senderOf(item, t)}>{from}</span>
            <span aria-hidden className="text-muted-foreground">·</span>
            <span className="text-muted-foreground shrink-0">
              <PostAgeText startAt={item.at} at={at} testId="inbox-age" title={formatDateTime(item.at)} />
            </span>
          </span>
          <span className={cn('mt-0.5', item.unread ? 'font-semibold' : 'font-normal')} lang={title.lang}>
            {title.text}
          </span>
          <span className="text-muted-foreground line-clamp-2" lang={body.lang}>{body.text}</span>
          {item.unread && <span className="sr-only" data-testid="inbox-unread">{t('inbox.unread')}</span>}
        </span>
      </Link>
    </li>
  )
}

/** A panel from the bottom of the screen (Q113d-1: the filters, the settings): a title and its contents. */
function Panel({ open, onOpenChange, title, description, testId, children }: {
  open: boolean; onOpenChange: (open: boolean) => void; title: string; description?: string; testId: string
  children: ReactNode }) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="bottom" className="mx-auto max-h-[85dvh] w-full max-w-[600px] gap-4 overflow-y-auto rounded-t-[16px] pb-6"
        data-testid={testId}>
        <SheetHeader className="pb-0">
          <SheetTitle>{title}</SheetTitle>
          <SheetDescription className={description ? undefined : 'sr-only'}>{description ?? title}</SheetDescription>
        </SheetHeader>
        <div className="flex flex-col gap-4 px-4">{children}</div>
      </SheetContent>
    </Sheet>
  )
}

/** The promotions switch (Q7-11: promotions only; system messages, rewards and reminders always come). */
function Promotions() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const queryClient = useQueryClient()
  const state = useQuery({ queryKey: STATE_QUERY, queryFn: readInboxState })
  const save = useMutation({
    mutationFn: saveInboxSettings,
    onSuccess: (next) => {
      queryClient.setQueryData(STATE_QUERY, next)
      void queryClient.invalidateQueries({ queryKey: INBOX_QUERY })
    },
  })
  return (
    <section aria-labelledby={`${id}-title`} className="flex flex-col gap-2" data-testid="inbox-settings">
      <h2 id={`${id}-title`} className="text-sm font-medium">{t('inbox.settings')}</h2>
      <div className="flex items-center gap-3">
        <Switch id={`${id}-promo`} checked={state.data ? !state.data.promoOff : true}
          disabled={!state.data || save.isPending} data-testid="inbox-promotions"
          aria-describedby={`${id}-hint`} onCheckedChange={(on) => save.mutate(!on)} />
        <Label htmlFor={`${id}-promo`}>{t('inbox.promotions')}</Label>
      </div>
      <p id={`${id}-hint`} className="text-muted-foreground text-xs">{t('inbox.promotionsHint')}</p>
      {save.isError && <p className="text-destructive text-sm" role="alert">{problemText(save.error)}</p>}
      {state.isError && <p className="text-destructive text-sm" role="alert">{tQb('failed')}</p>}
    </section>
  )
}

/**
 * The taker's inbox (U-60 – U-63; template `qb.app.inbox`): their messages and the announcements that match them,
 * newest first, filtered by kind, unread, text and dates; "mark all as read" and the promotions switch. Opening an
 * item reads it (its page marks it). Entering the page refreshes the navigation's badge. Q113d-1 (the prototype's
 * notifications): the area's tabs "All" and "Rewards" (the kind `REWARD`, at its own address) beside the
 * conversations; "mark all as read", the filters and the settings are the header's buttons, the filters and the
 * settings in a panel each.
 */
export function InboxPage({ tab }: { tab: 'inbox' | 'rewards' }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const queryClient = useQueryClient()
  const sponsor = !!useMySponsor().data
  // The list as of opening the page: its pages stay one list until it is opened again or reloaded.
  const [at] = useState(() => Date.now())
  const now = new Date(at).toISOString()
  const [text, setText] = useState('')
  const [chosen, setFilter] = useState<InboxFilter>({})
  const [done, setDone] = useState(false)
  const [filtering, setFiltering] = useState(false)
  const [setting, setSetting] = useState(false)
  // The rewards' tab is the kind REWARD, whatever was chosen in "All".
  const filter: InboxFilter = tab === 'rewards' ? { ...chosen, category: 'REWARD' } : chosen
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: UNREAD_QUERY })
  }, [queryClient])
  // Q59b: as the home, the sponsors' notices of the languages the taker sees (after reading the settings).
  const settings = useSoundSetting()
  const languages = shownLanguages(settings.data, i18n.language)
  const inbox = useInfiniteQuery({
    queryKey: [...INBOX_QUERY, 'list', at, filter, i18n.language, languages],
    queryFn: ({ pageParam }) => readInbox(i18n.language, filter, pageParam, INBOX_PAGE, languages),
    enabled: !settings.isPending,
    initialPageParam: 0,
    getNextPageParam: (last) => (last.items.length < INBOX_PAGE ? undefined : last.offset + INBOX_PAGE),
  })
  const all = useMutation({
    mutationFn: readAll,
    onSuccess: () => {
      setDone(true)
      void queryClient.invalidateQueries({ queryKey: INBOX_QUERY })
    },
  })
  const items = inbox.data?.pages.flatMap((page) => page.items) ?? []
  const search = (event: FormEvent) => {
    event.preventDefault()
    setFilter((current) => ({ ...current, q: text.trim() || undefined }))
  }
  const dated = !!(chosen.from || chosen.to)
  // What the taker chose (the rewards' tab's own kind is not a choice).
  const filtered = !!(chosen.q || (tab === 'inbox' && chosen.category) || chosen.unread || chosen.from || chosen.to)
  const iconButton = 'relative size-9 rounded-full'
  return (
    <section className="flex flex-col gap-4" aria-labelledby="inbox-title" data-testid="inbox">
      {/* Q82: the conversations with sponsors are the other tab (F-F4). */}
      <AreaHeader area="messages" tab={tab} id="inbox-title" title={t('frame.areas.messages')}
        actions={(
          <span className="flex" data-testid="inbox-tools">
            <Button variant="ghost" size="icon" className={iconButton} disabled={all.isPending}
              onClick={() => all.mutate()} aria-label={t('inbox.readAll')} title={t('inbox.readAll')}
              data-testid="inbox-read-all">
              <CheckCheck aria-hidden className="size-5" />
            </Button>
            <Button variant="ghost" size="icon" className={iconButton} onClick={() => setFiltering(true)}
              aria-label={filtered ? t('inbox.filterOn') : t('inbox.filter')} title={t('inbox.filter')}
              data-testid="inbox-filter-open" data-active={filtered || undefined}>
              <ListFilter aria-hidden className="size-5" />
              {filtered && <span aria-hidden className="bg-primary absolute top-1.5 right-1.5 size-2 rounded-full" />}
            </Button>
            <Button variant="ghost" size="icon" className={iconButton} onClick={() => setSetting(true)}
              aria-label={t('inbox.settingsOpen')} title={t('inbox.settingsOpen')} data-testid="inbox-settings-open">
              <Settings aria-hidden className="size-5" />
            </Button>
          </span>
        )} />
      <p role="status" className="text-muted-foreground text-sm empty:hidden" data-testid="inbox-read-all-done">
        {done ? t('inbox.readAllDone') : ''}
      </p>
      {all.isError && <p className="text-destructive text-sm" role="alert">{problemText(all.error)}</p>}
      <Panel open={filtering} onOpenChange={setFiltering} title={t('inbox.filterTitle')} testId="inbox-filters">
        <form role="search" onSubmit={search} className="relative flex min-w-0">
          <Label htmlFor={`${id}-q`} className="sr-only">{t('inbox.search')}</Label>
          <Input id={`${id}-q`} type="search" value={text} maxLength={100} placeholder={t('inbox.search')}
            className="rounded-full pr-9" data-testid="inbox-search" onChange={(event) => setText(event.target.value)} />
          <Button type="submit" variant="ghost" size="icon" aria-label={t('inbox.searchButton')}
            className="absolute inset-y-0 right-0 h-full rounded-full">
            <Search aria-hidden />
          </Button>
        </form>
        {tab === 'inbox' && (
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${id}-category`}>{t('inbox.show')}</Label>
            <Select value={chosen.category ?? 'all'}
              onValueChange={(value) => setFilter((current) => ({ ...current,
                category: value === 'all' ? undefined : value as Category }))}>
              <SelectTrigger id={`${id}-category`} data-testid="inbox-category" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {(sponsor ? SPONSOR_CATEGORIES : CATEGORIES).map((category) => (
                  <SelectItem key={category} value={category}>{t(`inbox.categories.${category}`)}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}
        <div className="flex items-center gap-2">
          <Checkbox id={`${id}-unread`} checked={!!chosen.unread} data-testid="inbox-unread-only"
            onCheckedChange={(on) => setFilter((current) => ({ ...current, unread: on === true || undefined }))} />
          <Label htmlFor={`${id}-unread`} className="font-normal">{t('inbox.unreadOnly')}</Label>
        </div>
        <fieldset className="flex flex-col gap-2" data-testid="inbox-dates" data-active={dated || undefined}>
          <legend className="mb-1.5 text-sm font-medium">{t('inbox.dates')}</legend>
          <div className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-2">
            <Label htmlFor={`${id}-from`} className="text-xs">{t('inbox.from')}</Label>
            <Input id={`${id}-from`} type="date" value={chosen.from ?? ''} data-testid="inbox-from"
              onChange={(event) => setFilter((current) => ({ ...current, from: event.target.value || undefined }))} />
            <Label htmlFor={`${id}-to`} className="text-xs">{t('inbox.to')}</Label>
            <Input id={`${id}-to`} type="date" value={chosen.to ?? ''} data-testid="inbox-to"
              onChange={(event) => setFilter((current) => ({ ...current, to: event.target.value || undefined }))} />
          </div>
          {dated && (
            <Button variant="ghost" size="sm" className="self-end" data-testid="inbox-dates-clear"
              onClick={() => setFilter((current) => ({ ...current, from: undefined, to: undefined }))}>
              {t('inbox.clearDates')}
            </Button>
          )}
        </fieldset>
        <Button className="rounded-full" onClick={() => setFiltering(false)} data-testid="inbox-filters-done">
          {t('inbox.filtersDone')}
        </Button>
      </Panel>
      <Panel open={setting} onOpenChange={setSetting} title={t('inbox.settingsOpen')} testId="inbox-settings-panel">
        <Promotions />
        <Link to={accountPath('mail')} className="text-primary font-medium underline-offset-2 hover:underline"
          data-testid="inbox-mail-settings">{t('account.titles.mail')}</Link>
      </Panel>
      {inbox.isPending ? (
        <PageState kind="loading" />
      ) : inbox.isError ? (
        <PageState kind="error" description={problemText(inbox.error)}
          action={<Button variant="outline" onClick={() => void inbox.refetch()}>{tQb('retry')}</Button>} />
      ) : items.length === 0 ? (
        <PageState kind="empty" title={t('inbox.emptyTitle')}
          description={filtered ? t('inbox.noMatch') : t('inbox.empty')} data-testid="inbox-empty" />
      ) : (
        <>
          {/* Q113d-1: rows across the column, a divider between them, as the home's posts. */}
          <ul className="border-border -mx-4 flex flex-col border-t" data-testid="inbox-list">
            {items.map((item) => <Row key={`${item.itemKind}:${item.itemId}`} item={item} at={now} />)}
          </ul>
          {inbox.hasNextPage && (
            <Button variant="outline" disabled={inbox.isFetchingNextPage} onClick={() => void inbox.fetchNextPage()}>
              {inbox.isFetchingNextPage ? <Spinner label={t('inbox.more')} /> : t('inbox.more')}
            </Button>
          )}
        </>
      )}
    </section>
  )
}
