import { formatDateTime, i18n } from '@jabiz/client'
import { TitleBadge } from '../pages/TitleBadge'
import {
  Badge,
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
  Spinner,
  Switch,
  cn,
} from '@jabiz/ui'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCheck, Inbox, Search } from 'lucide-react'
import { useEffect, useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
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

function Row({ item }: { item: InboxItem }) {
  const { t } = useTranslation(APP)
  const title = pickText(item.title, i18n.language)
  const body = pickText(item.body, i18n.language)
  return (
    <li data-testid="inbox-row" data-kind={item.itemKind} data-id={item.itemId} data-unread={item.unread}>
      <Link to={itemPath(item.itemKind, item.itemId)}
        className={cn('hover:bg-accent focus-visible:ring-ring/50 flex flex-col gap-1 rounded-lg border p-3',
          'outline-none focus-visible:ring-[3px]')}>
        <span className="flex items-start gap-2">
          <span className={cn('flex-1', item.unread ? 'font-semibold' : 'font-medium')} lang={title.lang}>
            {title.text}
          </span>
          {item.unread && <Badge data-testid="inbox-unread">{t('inbox.unread')}</Badge>}
        </span>
        <span className="text-muted-foreground line-clamp-2 text-sm" lang={body.lang}>{body.text}</span>
        <span className="text-muted-foreground flex flex-wrap gap-x-3 text-xs">
          <span>{senderOf(item, t)}</span>
          <span>{formatDateTime(item.at)}</span>
        </span>
      </Link>
    </li>
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
    <section aria-labelledby={`${id}-title`} className="flex flex-col gap-2 rounded-lg border p-3"
      data-testid="inbox-settings">
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
 * item reads it (its page marks it). Entering the page refreshes the navigation's badge.
 */
export function InboxPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const queryClient = useQueryClient()
  const sponsor = !!useMySponsor().data
  // The list as of opening the page: its pages stay one list until it is opened again or reloaded.
  const [at] = useState(() => Date.now())
  const [text, setText] = useState('')
  const [filter, setFilter] = useState<InboxFilter>({})
  const [done, setDone] = useState(false)
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
  const filtered = !!(filter.q || filter.category || filter.unread || filter.from || filter.to)
  return (
    <section className="flex flex-col gap-4" aria-labelledby="inbox-title" data-testid="inbox">
      <div className="flex items-center gap-3">
        <TitleBadge icon={Inbox} />
        <h1 id="inbox-title" className="mr-auto text-xl font-semibold">{t('inbox.title')}</h1>
        <Button variant="outline" size="sm" disabled={all.isPending} onClick={() => all.mutate()}
          data-testid="inbox-read-all">
          <CheckCheck aria-hidden />
          {t('inbox.readAll')}
        </Button>
      </div>
      <p role="status" className="text-muted-foreground text-sm empty:hidden" data-testid="inbox-read-all-done">
        {done ? t('inbox.readAllDone') : ''}
      </p>
      {all.isError && <p className="text-destructive text-sm" role="alert">{problemText(all.error)}</p>}
      <form role="search" onSubmit={search} className="flex gap-2">
        <Label htmlFor={`${id}-q`} className="sr-only">{t('inbox.search')}</Label>
        <Input id={`${id}-q`} type="search" value={text} maxLength={100} placeholder={t('inbox.search')}
          data-testid="inbox-search" onChange={(event) => setText(event.target.value)} />
        <Button type="submit" variant="outline" size="icon" aria-label={t('inbox.searchButton')}>
          <Search aria-hidden />
        </Button>
      </form>
      <div className="flex flex-wrap items-center gap-3">
        <Label htmlFor={`${id}-category`} className="sr-only">{t('inbox.show')}</Label>
        <Select value={filter.category ?? 'all'}
          onValueChange={(value) => setFilter((current) => ({ ...current,
            category: value === 'all' ? undefined : value as Category }))}>
          <SelectTrigger id={`${id}-category`} size="sm" data-testid="inbox-category" aria-label={t('inbox.show')}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {(sponsor ? SPONSOR_CATEGORIES : CATEGORIES).map((category) => (
              <SelectItem key={category} value={category}>{t(`inbox.categories.${category}`)}</SelectItem>
            ))}
          </SelectContent>
        </Select>
        <div className="flex items-center gap-2">
          <Checkbox id={`${id}-unread`} checked={!!filter.unread} data-testid="inbox-unread-only"
            onCheckedChange={(on) => setFilter((current) => ({ ...current, unread: on === true || undefined }))} />
          <Label htmlFor={`${id}-unread`}>{t('inbox.unreadOnly')}</Label>
        </div>
      </div>
      <fieldset className="flex flex-wrap items-end gap-3">
        <legend className="mb-1 text-sm font-medium">{t('inbox.dates')}</legend>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-from`} className="text-xs">{t('inbox.from')}</Label>
          <Input id={`${id}-from`} type="date" className="w-40" value={filter.from ?? ''} data-testid="inbox-from"
            onChange={(event) => setFilter((current) => ({ ...current, from: event.target.value || undefined }))} />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-to`} className="text-xs">{t('inbox.to')}</Label>
          <Input id={`${id}-to`} type="date" className="w-40" value={filter.to ?? ''} data-testid="inbox-to"
            onChange={(event) => setFilter((current) => ({ ...current, to: event.target.value || undefined }))} />
        </div>
      </fieldset>
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
          <ul className="flex flex-col gap-2" data-testid="inbox-list">
            {items.map((item) => <Row key={`${item.itemKind}:${item.itemId}`} item={item} />)}
          </ul>
          {inbox.hasNextPage && (
            <Button variant="outline" disabled={inbox.isFetchingNextPage} onClick={() => void inbox.fetchNextPage()}>
              {inbox.isFetchingNextPage ? <Spinner label={t('inbox.more')} /> : t('inbox.more')}
            </Button>
          )}
        </>
      )}
      <Promotions />
    </section>
  )
}
