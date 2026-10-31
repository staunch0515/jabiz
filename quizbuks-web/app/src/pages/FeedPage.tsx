import { i18n, type Filter } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
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
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
  Spinner,
  Switch,
} from '@jabiz/ui'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Gift, HandCoins, LayoutGrid, SlidersHorizontal, X, type LucideIcon } from 'lucide-react'
import { createContext, useCallback, useContext, useEffect, useId, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { EmptyArt } from '../qb/Illustrations'
import { FeedCardView, REWARD_METHOD_ICONS } from '../qb/view/FeedCard'
import { readCountries, shownLanguages } from '../qb/api'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { cardProgress, FEED_SORTS, readFeed, readOpenCashRewards, readOpenRedemptions, type FeedCard, type FeedSort, type RewardMethod }
  from '../qb/play'
import { problemText } from '../qb/problems'
import { Link, navigate, useLocation } from '../qb/router'
import { prefersStillVideo } from '../qb/video'
import { QBVIEW } from '../qb/view/messages'
import { openWithSound, VideoSlot } from '../video/stage'
import { useSoundSetting } from '../qb/SoundSettings'
import { HomeInstallOffer } from '../install'

/** The home's address, searched from the header (Q63): `/?q=<text>`. */
export const HOME_PATH = '/'

export function searchPath(q: string): string {
  return q ? `${HOME_PATH}?q=${encodeURIComponent(q)}` : HOME_PATH
}

/** The search an address of the home carries; empty for none. */
export function searchOf(search: string): string {
  return (new URLSearchParams(search).get('q') ?? '').trim().slice(0, 100)
}

/** Cards per page of the feed. */
export const FEED_PAGE = 10

/** The ways a reward is paid the home can be narrowed to (Q22): all, or one of the methods. */
export type MethodChoice = 'ALL' | RewardMethod
const METHOD_CHOICES: readonly MethodChoice[] = ['ALL', 'PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM', 'DIGITAL_PRIZE']
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

/** The icon of each choice of the reward menu (Q34): the menu's button shows the chosen one. */
export const METHOD_ICONS: Record<MethodChoice, LucideIcon> = { ALL: LayoutGrid, ...REWARD_METHOD_ICONS }

/** The ways a reward is paid, as chips at the top of the filters' sheet (Q64; Q63 had them in a row on the home). */
function MethodChoices({ value, onChange }: { value: MethodChoice; onChange: (choice: MethodChoice) => void }) {
  const { t } = useTranslation(APP)
  const label = (choice: MethodChoice) => (choice === 'ALL' ? t('methods.all') : t(`methods.${choice}Short`))
  return (
    <div role="group" aria-label={t('methods.choose')} className="flex flex-wrap gap-2" data-testid="feed-chips">
      {METHOD_CHOICES.map((choice) => {
        const Icon = METHOD_ICONS[choice]
        const on = choice === value
        return (
          <Button key={choice} type="button" size="sm" variant={on ? 'default' : 'secondary'} aria-pressed={on}
            className="rounded-full" data-testid={`feed-method-${choice}`} onClick={() => onChange(choice)}>
            <Icon aria-hidden />
            {label(choice)}
          </Button>
        )
      })}
    </div>
  )
}

/** The advanced filters (U-13); empty texts are no filter. */
export interface FeedFilters {
  /** The order (Q34: in the advanced filters, so that the search stays on one line). */
  sort: FeedSort
  /** Also the publications the taker answered (Q34: left out by default). */
  includeAnswered: boolean
  sponsor: string
  sponsorCountry: string | null
  rewardMin: string
  rewardMax: string
  minCompletions: string
}

export const NO_FILTERS: FeedFilters = { sort: 'newest', includeAnswered: false, sponsor: '', sponsorCountry: null, rewardMin: '', rewardMax: '',
  minCompletions: '' }

/** How many of the advanced filters differ from none (the button's count): the order and the answered count too. */
export function activeFilterCount(filters: FeedFilters): number {
  return feedFilters(filters).length + (filters.sort !== NO_FILTERS.sort ? 1 : 0) + (filters.includeAnswered ? 1 : 0)
}

/** A pattern of the template's LIKE that finds the text as written: % _ and \ match themselves. */
/**
 * The home's choices (Q64): the way a reward is paid and the advanced filters, chosen from the header's filter button
 * and read by the home; they last while the app is open (the way a reward is paid also on this device).
 */
interface FeedControls {
  method: MethodChoice
  chooseMethod: (choice: MethodChoice) => void
  filters: FeedFilters
  setFilters: (filters: FeedFilters | ((current: FeedFilters) => FeedFilters)) => void
  open: boolean
  setOpen: (open: boolean) => void
}

const FeedControlsContext = createContext<FeedControls | null>(null)

export function FeedControlsProvider({ children }: { children: ReactNode }) {
  const [method, setMethod] = useState<MethodChoice>(storedMethod)
  const [filters, setFilters] = useState<FeedFilters>(NO_FILTERS)
  const [open, setOpen] = useState(false)
  const value = useMemo<FeedControls>(() => ({
    method,
    chooseMethod: (choice) => {
      setMethod(choice)
      storeMethod(choice)
    },
    filters, setFilters, open, setOpen,
  }), [method, filters, open])
  return <FeedControlsContext.Provider value={value}>{children}</FeedControlsContext.Provider>
}

function useFeedControls(): FeedControls {
  const controls = useContext(FeedControlsContext)
  if (!controls) throw new Error('useFeedControls needs a <FeedControlsProvider> around it')
  return controls
}

/** The header's filter button on the home (Q64): a dot says something narrows the feed. */
export function FeedFiltersButton() {
  const { t } = useTranslation(APP)
  const { method, filters, setOpen } = useFeedControls()
  const count = activeFilterCount(filters) + (method === 'ALL' ? 0 : 1)
  return (
    <Button variant="ghost" size="icon-sm" className="relative" data-testid="feed-filters-open"
      aria-label={count > 0 ? `${t('feed.filters')}, ${t('feed.activeFilters', { count })}` : t('feed.filters')}
      onClick={() => setOpen(true)}>
      <SlidersHorizontal aria-hidden />
      {count > 0 && (
        <span aria-hidden className="bg-primary absolute top-0.5 right-0.5 size-2 rounded-full" data-testid="feed-filters-dot" />
      )}
    </Button>
  )
}

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

/**
 * The card in the middle of the screen (Q63): of the cards with a video, the one crossing the screen's middle band
 * plays; none while the viewer asked for less motion or the browser cannot tell.
 */
function useCentredVideo(list: HTMLElement | null, count: number): string | null {
  const [centred, setCentred] = useState<string | null>(null)
  useEffect(() => {
    if (!list || typeof IntersectionObserver === 'undefined' || prefersStillVideo()) return
    const inBand = new Set<string>()
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const id = (entry.target as HTMLElement).dataset.publication!
        if (entry.isIntersecting) inBand.add(id)
        else inBand.delete(id)
      }
      // The first in the list's order of those in the band.
      const first = Array.from(list.querySelectorAll<HTMLElement>('[data-video]'))
        .map((card) => card.dataset.publication!).find((id) => inBand.has(id))
      setCentred(first ?? null)
    }, { rootMargin: '-40% 0px -40% 0px' })
    list.querySelectorAll('[data-video]').forEach((card) => observer.observe(card))
    return () => observer.disconnect()
  }, [list, count])
  return centred
}

/** Asks for the next page as the end of the list comes near the screen (Q63), as the "more" button does. */
function useMoreNearTheEnd(sentinel: HTMLElement | null, more: () => void) {
  const latest = useRef(more)
  useEffect(() => {
    latest.current = more
  })
  useEffect(() => {
    if (!sentinel || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) latest.current()
    }, { rootMargin: '0px 0px 600px 0px' })
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [sentinel])
}

/**
 * The playing card's video (Q65): the app's one player, which nothing on the card touches; a tap on it opens the
 * start page with the sound on, the same player playing on.
 */
function CardVideo({ card, player }: { card: FeedCard; player: string }) {
  const { t } = useTranslation(QBVIEW)
  const open = useCallback(() => {
    openWithSound(player)
    navigate(`/p/${card.publicationId}`)
  }, [card.publicationId, player])
  return <VideoSlot id={`card:${card.publicationId}`} player={player} mode="card" onOpen={open}
    title={t('feed.videoTitle', { name: card.name })} />
}

/**
 * The taker's own progress on a card (Q64, Q66), in its line of facts: nothing before answering, else in progress or
 * answered. The start page it opens leads on: to start, to continue, or to the result.
 */
function CardProgressNote({ card, at }: { card: FeedCard; at: string }) {
  const { t } = useTranslation(APP)
  const progress = cardProgress(card, new Date(at))
  if (progress === 'start') return null
  return (
    <span className={progress === 'resume' ? 'text-primary font-medium' : undefined}
      data-testid="feed-card-progress" data-progress={progress}>{t(`feed.progress.${progress}`)}</span>
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
          <Label htmlFor={`${id}-sort`}>{t('feed.sort')}</Label>
          <Select value={draft.sort} onValueChange={(sort) => set({ sort: sort as FeedSort })}>
            <SelectTrigger id={`${id}-sort`} className="w-full" data-testid="feed-sort">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {(Object.keys(FEED_SORTS) as FeedSort[]).map((key) => (
                <SelectItem key={key} value={key}>{t(`feed.sorts.${key}`)}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex items-center justify-between gap-3">
          <Label htmlFor={`${id}-answered`} className="flex flex-col items-start gap-0.5">
            <span>{t('feed.includeAnswered')}</span>
            <span className="text-muted-foreground text-xs font-normal">{t('feed.includeAnsweredHint')}</span>
          </Label>
          <Switch id={`${id}-answered`} checked={draft.includeAnswered} data-testid="feed-filter-answered"
            onCheckedChange={(checked) => set({ includeAnswered: checked })} />
        </div>
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
function FiltersSheet({ open, onOpenChange, value, onApply, method, onMethod }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  value: FeedFilters
  onApply: (filters: FeedFilters) => void
  method: MethodChoice
  onMethod: (choice: MethodChoice) => void
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="bottom" className="mx-auto max-h-[90vh] max-w-md overflow-y-auto">
        {/* Q64: the way a reward is paid applies at once; the advanced filters with their button. */}
        <div className="px-4 pt-12"><MethodChoices value={method} onChange={onMethod} /></div>
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
  // The feed is for the moment the page opened; a search or a new sort asks again for that moment.
  const [at] = useState(() => new Date().toISOString())
  // Q63: searched from the header (`/?q=`).
  const q = searchOf(useLocation().search)
  // Q64: chosen from the header's filter button (Q22: one way of paying the reward, remembered on this device).
  const { method, chooseMethod, filters, setFilters, open: filtersOpen, setOpen: setFiltersOpen } = useFeedControls()
  const filterList = feedFilters(filters)
  const active = activeFilterCount(filters)
  // The cash rewards still waiting for the taker or the sponsor: a notice at the top, with each to look at.
  const cash = useQuery({ queryKey: ['qb', 'cash-rewards', 'open'], queryFn: readOpenCashRewards })
  const openCash = cash.data ?? []
  const prizes = useQuery({ queryKey: ['qb', 'redemptions', 'open'], queryFn: readOpenRedemptions })
  const openPrizes = prizes.data ?? []

  // Q59a: only the languages the taker switched on (the app's language until they choose).
  const settings = useSoundSetting()
  const languages = shownLanguages(settings.data, i18n.language)
  const feed = useInfiniteQuery({
    queryKey: ['qb', 'feed', at, country, q, filters.sort, filters.includeAnswered, method, filterList, i18n.language,
      languages],
    queryFn: ({ pageParam }) => readFeed({ at, country, q, sort: filters.sort, method: method === 'ALL' ? null : method,
      includeAnswered: filters.includeAnswered, languages, filters: filterList, offset: pageParam,
      limit: FEED_PAGE }),
    // The settings first: a feed of the wrong languages would show for a moment otherwise.
    enabled: !settings.isPending,
    initialPageParam: 0,
    getNextPageParam: (last) => (last.items.length < FEED_PAGE ? undefined : last.offset + FEED_PAGE),
  })
  const cards = feed.data?.pages.flatMap((page) => page.items) ?? []
  const [list, setList] = useState<HTMLUListElement | null>(null)
  const [end, setEnd] = useState<HTMLDivElement | null>(null)
  const playing = useCentredVideo(list, cards.length)
  useMoreNearTheEnd(end, () => {
    if (feed.hasNextPage && !feed.isFetchingNextPage) void feed.fetchNextPage()
  })

  return (
    <section className="flex flex-col" aria-labelledby="home-title" data-testid="home">
      {/* Q65: what comes before the cards takes room only when there is something to see: the first card is right
          under the header otherwise. */}
      <div className="flex flex-col gap-4 has-[>:not(.sr-only)]:pb-4" data-testid="home-top">
      {/* Q66: no greeting band; the page's heading is for assistive technology only. */}
      <h1 id="home-title" className="sr-only">{t('home.greeting', { name })}</h1>
      <HomeInstallOffer />
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
      {openPrizes.length > 0 && (
        <Alert data-testid="home-redeem">
          <Gift aria-hidden />
          <AlertTitle>{t('redeem.openTitle', { count: openPrizes.length })}</AlertTitle>
          <AlertDescription className="text-current">
            <ul className="flex flex-col gap-1">
              {openPrizes.map((r) => (
                <li key={r.redemptionId} data-testid="home-redeem-item" data-status={r.status}>
                  <Link to={`/result/${encodeURIComponent(r.attemptId)}`} className="underline underline-offset-4">
                    {t(`redeem.open.${r.status === 'CLAIMED' ? 'CLAIMED' : 'DUE'}`, { sponsor: r.sponsorCompany ?? t('cash.theSponsor'),
                      description: r.description, code: r.code })}
                  </Link>
                </li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
      {q && (
        <div className="flex items-center gap-2 text-sm" data-testid="feed-searched">
          <span className="min-w-0 truncate">{t('feed.searchedFor', { q })}</span>
          <Button variant="ghost" size="sm" onClick={() => navigate(HOME_PATH)} data-testid="feed-search-clear">
            <X aria-hidden />
            {t('feed.clearSearch')}
          </Button>
        </div>
      )}
      <FiltersSheet open={filtersOpen} onOpenChange={setFiltersOpen} value={filters} onApply={setFilters}
        method={method} onMethod={chooseMethod} />
      </div>
      {feed.isPending ? (
        <PageState kind="loading" />
      ) : feed.isError ? (
        <PageState kind="error" description={problemText(feed.error)}
          action={<Button variant="outline" onClick={() => void feed.refetch()}>{tQb('retry')}</Button>} />
      ) : cards.length === 0 ? (
        <div className="flex flex-col items-center gap-2">
          <EmptyArt className="h-32 w-40" />
          <PageState kind="empty" title={t('home.title')} className="py-2"
            description={q || active > 0 || method !== 'ALL' ? t('feed.noMatch') : t('home.empty')}
            data-testid="home-empty" />
          {/* Q34: the answered ones are left out by default; say so where nothing is left, and offer them. */}
          {!filters.includeAnswered && (
            <div className="flex flex-col items-center gap-2 text-center" data-testid="home-answered-hidden">
              <p className="text-muted-foreground text-sm">{t('feed.answeredHidden')}</p>
              <Button variant="outline" size="sm" data-testid="home-show-answered"
                onClick={() => setFilters((current) => ({ ...current, includeAnswered: true }))}>
                {t('feed.includeAnswered')}
              </Button>
            </div>
          )}
        </div>
      ) : (
        <>
          {/* Q63: one card a row across the screen; the one with a video in the middle of the screen plays. */}
          <ul ref={setList} className="-mx-4 flex flex-col gap-4 sm:mx-0" data-testid="feed-list"
            aria-label={t('feed.listTitle')}>
            {cards.map((card) => (
              <FeedCardView key={card.publicationId} card={card} href={`/p/${card.publicationId}`}
                playing={playing === card.publicationId} note={<CardProgressNote card={card} at={at} />}
                video={card.video && <CardVideo card={card} player={card.video} />}
                onOpen={card.video ? () => openWithSound(card.video!) : undefined} />
            ))}
          </ul>
          <div ref={setEnd} aria-hidden data-testid="feed-end" />
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
