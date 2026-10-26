import { i18n, type Filter } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Button,
  Combobox,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
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
  cn,
} from '@jabiz/ui'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Banknote, Coins, Gift, HandCoins, LayoutGrid, Play, Search, SlidersHorizontal, Ticket, Trophy, Users, type LucideIcon }
  from 'lucide-react'
import { useEffect, useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { playProblemText } from '../play/busy'
import { LazyImage } from '../play/LazyImage'
import { CoverPlaceholder, EmptyArt, QuizArt } from '../qb/Illustrations'
import { useStartAttempt } from '../play/useStartAttempt'
import { VideoPlayer } from '../play/VideoPlayer'
import { readCountries } from '../qb/api'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { FEED_SORTS, readFeed, readOpenCashRewards, readOpenRedemptions, type FeedCard, type FeedSort, type RewardMethod }
  from '../qb/play'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'

/** Cards per page of the feed. */
export const FEED_PAGE = 20

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
export const METHOD_ICONS: Record<MethodChoice, LucideIcon> = {
  ALL: LayoutGrid,
  PLATFORM: Coins,
  SPONSOR_CASH: Banknote,
  OFFLINE_REDEEM: Gift,
  DIGITAL_PRIZE: Ticket,
}

/**
 * The reward menu in front of the search box (Q34): an icon button that opens the five choices with their icons; the
 * button shows the chosen one's icon and says its name to assistive technology.
 */
function MethodMenu({ value, onChange }: { value: MethodChoice; onChange: (choice: MethodChoice) => void }) {
  const { t } = useTranslation(APP)
  const label = (choice: MethodChoice) => (choice === 'ALL' ? t('methods.all') : t(`methods.${choice}`))
  const Icon = METHOD_ICONS[value]
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant={value === 'ALL' ? 'outline' : 'secondary'} size="icon" className="shrink-0"
          aria-label={t('methods.menu', { method: label(value) })} data-testid="feed-method" data-method={value}>
          <Icon aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="min-w-56">
        <DropdownMenuLabel>{t('methods.choose')}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuRadioGroup value={value} onValueChange={(choice) => onChange(choice as MethodChoice)}>
          {METHOD_CHOICES.map((choice) => {
            const ItemIcon = METHOD_ICONS[choice]
            return (
              <DropdownMenuRadioItem key={choice} value={choice} data-testid={`feed-method-${choice}`}>
                <ItemIcon aria-hidden className="text-muted-foreground" />
                {label(choice)}
              </DropdownMenuRadioItem>
            )
          })}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
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

/** The reward on a card's picture: what passing earns (a survey: what answering earns). */
function RewardTag({ card }: { card: FeedCard }) {
  const { t } = useTranslation(APP)
  return (
    <span className="bg-primary text-primary-foreground inline-flex items-center gap-1 px-2 py-0.5 text-xs font-semibold shadow-sm"
      data-testid="feed-card-reward">
      <Trophy aria-hidden className="size-3" />
      {card.kind === 'SURVEY' ? t('feed.surveyReward', { amount: formatKudos(card.maxReward) })
        : t('feed.upTo', { amount: formatKudos(card.maxReward) })}
    </span>
  )
}

/**
 * One card of the picture grid (Q34; U-10): the cover fills the whole card (a drawn placeholder without one), the
 * reward and the way it is paid sit on its top, the name, the sponsor and how many answered on a dark band at its
 * foot, so the feed reads as pictures.
 */
function Card({ card }: { card: FeedCard }) {
  const { t } = useTranslation(APP)
  const sponsor = card.sponsorCompany ?? card.sponsorName
  const MethodIcon = METHOD_ICONS[card.rewardMethod]
  return (
    <li>
      <Link to={`/p/${card.publicationId}`} data-testid="feed-card"
        className="group bg-card focus-visible:ring-ring/50 relative block aspect-[4/5] overflow-hidden border shadow-sm outline-none transition-shadow hover:shadow-md focus-visible:ring-[3px]">
        {card.cover
          ? <LazyImage fileId={card.cover} variant="w320" alt=""
            className="absolute inset-0 size-full transition-transform duration-300 group-hover:scale-105" />
          : <CoverPlaceholder seed={card.publicationId} className="absolute inset-0 size-full"
            data-testid="feed-card-placeholder" />}
        <span className="absolute inset-x-0 top-0 flex items-start justify-between gap-1 p-1.5">
          <span className="flex flex-col items-start gap-1">
            {card.rewardMethod !== 'PLATFORM' && (
              <span className="bg-background/90 text-foreground inline-flex items-center gap-1 px-1.5 py-0.5 text-[11px] font-medium"
                data-testid="feed-card-method" data-method={card.rewardMethod}>
                <MethodIcon aria-hidden className="size-3" />
                {t(`methods.${card.rewardMethod}Short`)}
              </span>
            )}
            {card.kind === 'SURVEY' && (
              <span className="bg-background/90 text-foreground px-1.5 py-0.5 text-[11px] font-medium"
                data-testid="feed-card-survey">{t('feed.survey')}</span>
            )}
          </span>
          <RewardTag card={card} />
        </span>
        <span className="from-qb-hero-from via-qb-hero-from/85 text-qb-hero-foreground absolute inset-x-0 bottom-0 flex flex-col gap-0.5 bg-linear-to-t to-transparent px-2 pt-8 pb-2">
          <span className="line-clamp-2 text-sm leading-tight font-semibold" data-testid="feed-card-name">{card.name}</span>
          <span className="text-qb-hero-muted flex min-w-0 items-center gap-2 text-[11px]">
            {sponsor && (
              <span className="flex min-w-0 items-center gap-1">
                {card.sponsorAvatar && (
                  <LazyImage fileId={card.sponsorAvatar} variant="w128" alt="" className="size-3.5 shrink-0 rounded-full" />
                )}
                <span className="truncate">{sponsor}</span>
              </span>
            )}
            <span className="ml-auto flex shrink-0 items-center gap-0.5" data-testid="feed-card-completions">
              <Users aria-hidden className="size-3" />
              <span aria-hidden>{card.completions}</span>
              <span className="sr-only">{t('feed.completions', { count: card.completions })}</span>
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
    <li className="bg-card col-span-2 flex flex-col gap-2 border p-2 shadow-sm" data-testid="feed-video-card">
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

/** How long the greeting stays before it folds away (Q34). */
export const GREETING_MS = 2000
/** How long the fold takes (the band's `duration-500`). */
const FOLD_MS = 500
const GREETED_KEY = 'qb.home.greeted'

function greetedAlready(): boolean {
  try {
    return window.sessionStorage.getItem(GREETED_KEY) === '1'
  } catch {
    return false
  }
}

function rememberGreeted() {
  try {
    window.sessionStorage.setItem(GREETED_KEY, '1')
  } catch {
    // Blocked storage: the greeting comes again on the next visit of the home, which does no harm.
  }
}

function reducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

/**
 * The greeting band (Q34): shown once per session (a return to the home does not repeat it), for two seconds, then it
 * folds away so the quizzes take the screen; without the motion when the taker asked for less. The page's heading
 * stays for assistive technology after the band is gone.
 */
function Greeting({ name }: { name: string }) {
  const { t } = useTranslation(APP)
  const [shown, setShown] = useState(() => !greetedAlready())
  const [folding, setFolding] = useState(false)
  useEffect(() => {
    if (!shown) return undefined
    rememberGreeted()
    const timer = window.setTimeout(() => {
      if (reducedMotion()) setShown(false)
      else setFolding(true)
    }, GREETING_MS)
    // Should the end of the fold never be told (no transitions, a hidden tab), the band goes all the same.
    const fallback = window.setTimeout(() => setShown(false), GREETING_MS + FOLD_MS + 100)
    return () => {
      window.clearTimeout(timer)
      window.clearTimeout(fallback)
    }
  }, [shown])
  if (!shown) return <h1 id="home-title" className="sr-only">{t('home.greeting', { name })}</h1>
  return (
    <div className={cn('grid transition-[grid-template-rows,opacity,margin] duration-500 ease-out',
      folding ? '-mb-4 grid-rows-[0fr] opacity-0' : 'grid-rows-[1fr] opacity-100')}
      onTransitionEnd={(event) => {
        if (folding && event.target === event.currentTarget && event.propertyName === 'grid-template-rows') setShown(false)
      }} data-testid="home-hero" data-folding={folding || undefined}>
      <div className="overflow-hidden">
        <div className="from-qb-hero-from to-qb-hero-to text-qb-hero-foreground relative overflow-hidden bg-linear-to-br p-4 shadow-sm">
          {/* The text column ends where the illustration begins (pr-32 ≥ its 128px), so words never sit on it. */}
          <div className="relative z-10 flex flex-col gap-1 pr-32">
            <h1 id="home-title" className="text-xl font-semibold">{t('home.greeting', { name })}</h1>
            <p className="text-qb-hero-muted text-sm">{t('home.tagline')}</p>
          </div>
          <QuizArt className="absolute -right-3 -bottom-5 h-28 w-35 opacity-95" />
        </div>
      </div>
    </div>
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
  const [filters, setFilters] = useState<FeedFilters>(NO_FILTERS)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const filterList = feedFilters(filters)
  const active = activeFilterCount(filters)
  // Q22: narrowed to one way of paying the reward; remembered on this device.
  const [method, setMethod] = useState<MethodChoice>(storedMethod)
  const chooseMethod = (choice: MethodChoice) => {
    setMethod(choice)
    storeMethod(choice)
  }
  // The cash rewards still waiting for the taker or the sponsor: a notice at the top, with each to look at.
  const cash = useQuery({ queryKey: ['qb', 'cash-rewards', 'open'], queryFn: readOpenCashRewards })
  const openCash = cash.data ?? []
  const prizes = useQuery({ queryKey: ['qb', 'redemptions', 'open'], queryFn: readOpenRedemptions })
  const openPrizes = prizes.data ?? []

  const feed = useInfiniteQuery({
    queryKey: ['qb', 'feed', at, country, q, filters.sort, filters.includeAnswered, method, filterList, i18n.language],
    queryFn: ({ pageParam }) => readFeed({ at, country, q, sort: filters.sort, method: method === 'ALL' ? null : method,
      includeAnswered: filters.includeAnswered, filters: filterList, offset: pageParam, limit: FEED_PAGE }),
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
      <Greeting name={name} />
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
      {/* Q34: one line, the reward menu in front of the box, the advanced filters (with the order) behind the search. */}
      <form role="search" onSubmit={search} className="flex items-center gap-2">
        <MethodMenu value={method} onChange={chooseMethod} />
        <Label htmlFor={`${id}-q`} className="sr-only">{t('feed.search')}</Label>
        <Input id={`${id}-q`} type="search" value={text} maxLength={100} placeholder={t('feed.search')}
          className="min-w-0 flex-1" data-testid="feed-search" onChange={(event) => setText(event.target.value)} />
        <Button type="submit" size="icon" className="shrink-0" aria-label={t('feed.searchButton')}
          data-testid="feed-search-submit">
          <Search aria-hidden />
        </Button>
        <Button type="button" variant={active > 0 ? 'secondary' : 'outline'} size="icon" className="relative shrink-0"
          aria-label={active > 0 ? `${t('feed.filters')}, ${t('feed.activeFilters', { count: active })}` : t('feed.filters')}
          onClick={() => setFiltersOpen(true)} data-testid="feed-filters-open">
          <SlidersHorizontal aria-hidden />
          {active > 0 && (
            <span aria-hidden className="bg-primary text-primary-foreground absolute -top-1.5 -right-1.5 inline-flex size-4 items-center justify-center rounded-full text-[10px] font-semibold"
              data-testid="feed-filters-count">{active}</span>
          )}
        </Button>
      </form>
      <FiltersSheet open={filtersOpen} onOpenChange={setFiltersOpen} value={filters} onApply={setFilters} />
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
          <h2 className="sr-only">{t('feed.listTitle')}</h2>
          <ul className="grid grid-cols-2 gap-3" data-testid="feed-list">
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
