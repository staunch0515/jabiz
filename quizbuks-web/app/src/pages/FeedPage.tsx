import { i18n, type Filter } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  Combobox,
  Input,
  Label,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  SegmentedControl,
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
  Spinner,
} from '@jabiz/ui'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Banknote, Gift, HandCoins, Play, Search, SlidersHorizontal, Trophy, Users } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { playProblemText } from '../play/busy'
import { LazyImage } from '../play/LazyImage'
import { useStartAttempt } from '../play/useStartAttempt'
import { VideoPlayer } from '../play/VideoPlayer'
import { readCountries } from '../qb/api'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { FEED_SORTS, readFeed, readOpenCashRewards, type FeedCard, type FeedSort, type RewardMethod } from '../qb/play'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'

/** Cards per page of the feed. */
export const FEED_PAGE = 20

/** The ways a reward is paid the home can be narrowed to (Q22): all, or one of the methods. */
export type MethodChoice = 'ALL' | RewardMethod
const METHOD_CHOICES: readonly MethodChoice[] = ['ALL', 'PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM']
const METHOD_KEY = 'qb.feed.method'

/** The method chosen last on this device; all when none or when the browser keeps nothing. */
export function storedMethod(): MethodChoice {
  try {
    const stored = window.localStorage.getItem(METHOD_KEY)
    return (METHOD_CHOICES as readonly string[]).includes(stored ?? '') ? (stored as MethodChoice) : 'ALL'
  } catch {
    return 'ALL'
  }
}

function storeMethod(choice: MethodChoice) {
  try {
    window.localStorage.setItem(METHOD_KEY, choice)
  } catch {
    // A private window or blocked storage: the choice lasts for the page only.
  }
}

/** The badge of a reward paid otherwise than in Kudos by QuizBuks (Q22); none for the platform's Kudos. */
export function MethodBadge({ method, testId }: { method: RewardMethod; testId?: string }) {
  const { t } = useTranslation(APP)
  if (method === 'PLATFORM') return null
  const Icon = method === 'SPONSOR_CASH' ? Banknote : Gift
  return (
    <Badge variant="outline" className="shrink-0" data-testid={testId ?? 'method-badge'} data-method={method}>
      <Icon aria-hidden className="size-3" />
      {t(`methods.${method}`)}
    </Badge>
  )
}

/** The advanced filters (U-13); empty texts are no filter. */
export interface FeedFilters {
  sponsor: string
  sponsorCountry: string | null
  rewardMin: string
  rewardMax: string
  minCompletions: string
}

export const NO_FILTERS: FeedFilters = { sponsor: '', sponsorCountry: null, rewardMin: '', rewardMax: '',
  minCompletions: '' }

/** A pattern of the template's LIKE that finds the text as written: % _ and \ match themselves. */
export function containsPattern(text: string): string {
  return `%${text.replace(/[\\%_]/g, (c) => `\\${c}`)}%`
}

/** A whole number typed by the taker, or null when the field is empty or not a number. */
function wholeNumber(text: string): number | null {
  const trimmed = text.trim()
  if (!/^\d{1,13}$/.test(trimmed)) return null
  return Number(trimmed)
}

/** The template's filters of the advanced filters. */
export function feedFilters(filters: FeedFilters): Filter[] {
  const result: Filter[] = []
  const sponsor = filters.sponsor.trim()
  if (sponsor) result.push({ field: 'sponsorCompany', op: 'like', value: containsPattern(sponsor) })
  if (filters.sponsorCountry) result.push({ field: 'sponsorCountry', op: 'eq', value: filters.sponsorCountry })
  const min = wholeNumber(filters.rewardMin)
  const max = wholeNumber(filters.rewardMax)
  if (min !== null) result.push({ field: 'maxReward', op: 'gte', value: min })
  if (max !== null) result.push({ field: 'maxReward', op: 'lte', value: max })
  const completions = wholeNumber(filters.minCompletions)
  if (completions !== null) result.push({ field: 'completions', op: 'gte', value: completions })
  return result
}

/** One compact card (U-10: at least six on a phone's screen without scrolling). */
function Card({ card }: { card: FeedCard }) {
  const { t } = useTranslation(APP)
  const sponsor = card.sponsorCompany ?? card.sponsorName
  return (
    <li>
      <Link to={`/p/${card.publicationId}`} data-testid="feed-card"
        className="hover:bg-accent focus-visible:ring-ring/50 flex items-center gap-3 rounded-lg border p-2 outline-none focus-visible:ring-[3px]">
        {card.cover
          ? <LazyImage fileId={card.cover} variant="w320" alt="" className="size-16 shrink-0 rounded-md" />
          : <span aria-hidden className="bg-muted size-16 shrink-0 rounded-md" />}
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="flex min-w-0 items-center gap-2">
            <span className="truncate font-medium" data-testid="feed-card-name">{card.name}</span>
            {card.kind === 'SURVEY' && (
              <Badge variant="secondary" className="shrink-0" data-testid="feed-card-survey">{t('feed.survey')}</Badge>
            )}
            <MethodBadge method={card.rewardMethod} testId="feed-card-method" />
          </span>
          {sponsor && (
            <span className="text-muted-foreground flex items-center gap-1.5 truncate text-xs">
              {card.sponsorAvatar && (
                <LazyImage fileId={card.sponsorAvatar} variant="w128" alt="" className="size-4 shrink-0 rounded-full" />
              )}
              <span className="truncate">{sponsor}</span>
            </span>
          )}
          <span className="flex items-center gap-3 text-xs">
            <span className="text-primary flex items-center gap-1 font-semibold">
              <Trophy aria-hidden className="size-3.5" />
              {card.kind === 'SURVEY' ? t('feed.surveyReward', { amount: formatKudos(card.maxReward) })
                : t('feed.upTo', { amount: formatKudos(card.maxReward) })}
            </span>
            <span className="text-muted-foreground flex items-center gap-1">
              <Users aria-hidden className="size-3.5" />
              {t('feed.completions', { count: card.completions })}
            </span>
          </span>
        </span>
      </Link>
    </li>
  )
}

/**
 * A card whose quiz is answered by watching a video (Q19): the video's own player (it plays when the taker presses its
 * button, never by itself), what answering earns, and the start. The name still leads to the publication's page.
 */
function VideoCard({ card, player }: { card: FeedCard; player: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const start = useStartAttempt()
  const hint = `feed-video-hint-${card.publicationId}`
  return (
    <li className="flex flex-col gap-2 rounded-lg border p-2" data-testid="feed-video-card">
      <VideoPlayer player={player} title={t('feed.videoTitle', { name: card.name })} />
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          <Link to={`/p/${card.publicationId}`} className="truncate font-medium underline-offset-4 hover:underline"
            data-testid="feed-card-name">
            {card.name}
          </Link>
          <span id={hint} className="text-primary flex items-center gap-1 text-sm font-semibold"
            data-testid="feed-video-reward">
            <Trophy aria-hidden className="size-3.5" />
            {card.kind === 'SURVEY' ? t('feed.videoSurveyReward', { amount: formatKudos(card.maxReward) })
              : t('feed.videoReward', { amount: formatKudos(card.maxReward) })}
          </span>
        </div>
        <Button disabled={start.isPending} aria-busy={start.isPending || undefined} aria-describedby={hint}
          data-testid="feed-video-start" onClick={() => start.mutate(card.publicationId)}>
          <Play aria-hidden />
          {t('detail.start')}
        </Button>
      </div>
      <p className="text-muted-foreground text-sm empty:hidden" role="status">{start.busy ? t('play.busy') : ''}</p>
      {start.isError && (
        <p className="text-destructive text-sm" role="alert" data-testid="feed-video-error">
          {playProblemText(start.error) || tQb('failed')}
        </p>
      )}
    </li>
  )
}

/** The advanced filters' form; it starts from the filters applied each time the sheet opens. */
function FiltersForm({ value, onApply }: { value: FeedFilters; onApply: (filters: FeedFilters) => void }) {
  const { t, i18n: { language } } = useTranslation(APP)
  const id = useId()
  const [draft, setDraft] = useState(value)
  const countries = useQuery({ queryKey: ['qb', 'countries'], queryFn: readCountries, staleTime: Infinity })
  const options = (countries.data ?? [])
    .map((c) => ({ value: c.code, label: c.name[language] ?? c.name.en ?? c.code }))
    .sort((a, b) => a.label.localeCompare(b.label, language))
  const set = (patch: Partial<FeedFilters>) => setDraft((current) => ({ ...current, ...patch }))
  const submit = (event: FormEvent) => {
    event.preventDefault()
    onApply(draft)
  }
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4" data-testid="feed-filters">
      <SheetHeader>
        <SheetTitle>{t('feed.filters')}</SheetTitle>
        <SheetDescription>{t('feed.filtersHint')}</SheetDescription>
      </SheetHeader>
      <div className="flex flex-col gap-4 px-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-sponsor`}>{t('feed.sponsor')}</Label>
          <Input id={`${id}-sponsor`} value={draft.sponsor} maxLength={100} data-testid="feed-filter-sponsor"
            onChange={(event) => set({ sponsor: event.target.value })} />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-country`}>{t('feed.sponsorCountry')}</Label>
          <Combobox id={`${id}-country`} value={draft.sponsorCountry}
            onChange={(code) => set({ sponsorCountry: code })} options={options}
            placeholder={t('feed.anyCountry')} clearable searchPlaceholder={t('profile.searchCountry')}
            emptyText={t('profile.noCountry')} data-testid="feed-filter-country" />
        </div>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2 text-sm font-medium">{t('feed.reward')}</legend>
          <div className="flex items-center gap-2">
            <Label htmlFor={`${id}-min`} className="sr-only">{t('feed.rewardMin')}</Label>
            <Input id={`${id}-min`} value={draft.rewardMin} inputMode="numeric" placeholder={t('feed.rewardMin')}
              data-testid="feed-filter-reward-min" onChange={(event) => set({ rewardMin: event.target.value })} />
            <span aria-hidden>–</span>
            <Label htmlFor={`${id}-max`} className="sr-only">{t('feed.rewardMax')}</Label>
            <Input id={`${id}-max`} value={draft.rewardMax} inputMode="numeric" placeholder={t('feed.rewardMax')}
              data-testid="feed-filter-reward-max" onChange={(event) => set({ rewardMax: event.target.value })} />
          </div>
        </fieldset>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-completions`}>{t('feed.minCompletions')}</Label>
          <Input id={`${id}-completions`} value={draft.minCompletions} inputMode="numeric"
            data-testid="feed-filter-completions" onChange={(event) => set({ minCompletions: event.target.value })} />
        </div>
      </div>
      <SheetFooter className="flex-row justify-end">
        <Button type="button" variant="outline" data-testid="feed-filters-clear"
          onClick={() => setDraft(NO_FILTERS)}>{t('feed.clear')}</Button>
        <Button type="submit" data-testid="feed-filters-apply">{t('feed.apply')}</Button>
      </SheetFooter>
    </form>
  )
}

/** The advanced filters in a sheet from the bottom; applied together. */
function FiltersSheet({ open, onOpenChange, value, onApply }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  value: FeedFilters
  onApply: (filters: FeedFilters) => void
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="bottom" className="mx-auto max-h-[90vh] max-w-md overflow-y-auto">
        <FiltersForm value={value} onApply={(filters) => {
          onApply(filters)
          onOpenChange(false)
        }} />
      </SheetContent>
    </Sheet>
  )
}

/**
 * Home: the publications the taker can answer now (U-10 – U-14; template `qb.app.feed`), newest first, searched by
 * name and title, sorted and filtered; more on request. The country is the profile's: without one (an unverified
 * taker who has not completed it) only publications aimed at everybody.
 */
export function FeedPage({ name, country }: { name: string; country: string | null }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  // The feed is for the moment the page opened; a search or a new sort asks again for that moment.
  const [at] = useState(() => new Date().toISOString())
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [sort, setSort] = useState<FeedSort>('newest')
  const [filters, setFilters] = useState<FeedFilters>(NO_FILTERS)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const filterList = feedFilters(filters)
  // Q22: narrowed to one way of paying the reward; remembered on this device.
  const [method, setMethod] = useState<MethodChoice>(storedMethod)
  const chooseMethod = (choice: MethodChoice) => {
    setMethod(choice)
    storeMethod(choice)
  }
  // The cash rewards still waiting for the taker or the sponsor: a notice at the top, with each to look at.
  const cash = useQuery({ queryKey: ['qb', 'cash-rewards', 'open'], queryFn: readOpenCashRewards })
  const openCash = cash.data ?? []

  const feed = useInfiniteQuery({
    queryKey: ['qb', 'feed', at, country, q, sort, method, filterList, i18n.language],
    queryFn: ({ pageParam }) => readFeed({ at, country, q, sort, method: method === 'ALL' ? null : method,
      filters: filterList, offset: pageParam, limit: FEED_PAGE }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.items.length < FEED_PAGE ? undefined : last.offset + FEED_PAGE),
  })
  const cards = feed.data?.pages.flatMap((page) => page.items) ?? []

  const search = (event: FormEvent) => {
    event.preventDefault()
    setQ(text.trim())
  }

  return (
    <section className="flex flex-col gap-4" aria-labelledby="home-title" data-testid="home">
      <h1 id="home-title" className="text-xl font-semibold">{t('home.greeting', { name })}</h1>
      {openCash.length > 0 && (
        <Alert variant={openCash.some((r) => r.status === 'OVERDUE') ? 'warning' : 'default'} data-testid="home-cash">
          <HandCoins aria-hidden />
          <AlertTitle>{t('cash.openTitle', { count: openCash.length })}</AlertTitle>
          <AlertDescription className="text-current">
            <ul className="flex flex-col gap-1">
              {openCash.map((r) => (
                <li key={r.cashRewardId} data-testid="home-cash-item" data-status={r.status}>
                  <Link to={`/result/${encodeURIComponent(r.attemptId)}`} className="underline underline-offset-4">
                    {t(`cash.open.${r.status}`, { name: r.publicationName, amount: formatKudos(r.amount), code: r.code })}
                  </Link>
                </li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
      <form role="search" onSubmit={search} className="flex gap-2">
        <Label htmlFor={`${id}-q`} className="sr-only">{t('feed.search')}</Label>
        <Input id={`${id}-q`} type="search" value={text} maxLength={100} placeholder={t('feed.search')}
          data-testid="feed-search" onChange={(event) => setText(event.target.value)} />
        <Button type="submit" variant="outline" size="icon" aria-label={t('feed.searchButton')}
          data-testid="feed-search-submit">
          <Search aria-hidden />
        </Button>
      </form>
      <div className="flex items-center gap-2">
        <Label htmlFor={`${id}-sort`} className="sr-only">{t('feed.sort')}</Label>
        <Select value={sort} onValueChange={(value) => setSort(value as FeedSort)}>
          <SelectTrigger id={`${id}-sort`} className="flex-1" data-testid="feed-sort">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {(Object.keys(FEED_SORTS) as FeedSort[]).map((key) => (
              <SelectItem key={key} value={key}>{t(`feed.sorts.${key}`)}</SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button variant="outline" onClick={() => setFiltersOpen(true)} data-testid="feed-filters-open">
          <SlidersHorizontal aria-hidden />
          {t('feed.filters')}
          {filterList.length > 0 && (
            <Badge variant="secondary">
              <span aria-hidden>{filterList.length}</span>
              <span className="sr-only">{t('feed.activeFilters', { count: filterList.length })}</span>
            </Badge>
          )}
        </Button>
      </div>
      <SegmentedControl value={method} onChange={chooseMethod} aria-label={t('methods.choose')}
        data-testid="feed-method" className="w-full" options={METHOD_CHOICES.map((choice) => ({
          value: choice, label: choice === 'ALL' ? t('methods.all') : t(`methods.${choice}Short`) }))} />
      <FiltersSheet open={filtersOpen} onOpenChange={setFiltersOpen} value={filters} onApply={setFilters} />
      {feed.isPending ? (
        <PageState kind="loading" />
      ) : feed.isError ? (
        <PageState kind="error" description={problemText(feed.error)}
          action={<Button variant="outline" onClick={() => void feed.refetch()}>{tQb('retry')}</Button>} />
      ) : cards.length === 0 ? (
        <PageState kind="empty" title={t('home.title')}
          description={q || filterList.length > 0 ? t('feed.noMatch') : t('home.empty')} data-testid="home-empty" />
      ) : (
        <>
          <h2 className="sr-only">{t('feed.listTitle')}</h2>
          <ul className="flex flex-col gap-2" data-testid="feed-list">
            {cards.map((card) => (card.video
              ? <VideoCard key={card.publicationId} card={card} player={card.video} />
              : <Card key={card.publicationId} card={card} />))}
          </ul>
          {feed.hasNextPage && (
            <Button variant="outline" disabled={feed.isFetchingNextPage} data-testid="feed-more"
              onClick={() => void feed.fetchNextPage()}>
              {feed.isFetchingNextPage ? <Spinner label={t('feed.more')} /> : t('feed.more')}
            </Button>
          )}
        </>
      )}
    </section>
  )
}
