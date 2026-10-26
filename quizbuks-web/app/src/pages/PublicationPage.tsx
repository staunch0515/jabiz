import { formatDateTime, i18n, useAuth } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Button,
  MarkdownView,
  PageState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  cn,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, BookOpen, CalendarClock, ChevronDown, ListChecks, MailCheck, MailWarning, Play, Store, Target,
  Timer, TrendingUp, Trophy, type LucideIcon } from 'lucide-react'
import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { playProblemText } from '../play/busy'
import { useStartAttempt } from '../play/useStartAttempt'
import { FileImage } from '../qb/FileImage'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { readPublication, readTiers, recordView, type PublicationView, type Tier } from '../qb/play'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { CoverPlaceholder } from '../qb/Illustrations'
import { DetailCard } from './DetailCard'
import { METHOD_ICONS } from './FeedPage'
import { Materials } from './Materials'

/** Whole minutes from now until a time, at least 0 (by the device's clock: a hint only). */
export function minutesUntil(time: string | null, now: number = Date.now()): number {
  const end = time ? Date.parse(time) : NaN
  return Number.isFinite(end) ? Math.max(0, Math.ceil((end - now) / 60_000)) : 0
}

/** The time limit as people read it: minutes, with seconds when not whole. */
export function useDuration() {
  const { t } = useTranslation(APP)
  return (seconds: number) => {
    const minutes = Math.floor(seconds / 60)
    const rest = seconds % 60
    if (minutes === 0) return t('detail.seconds', { count: rest })
    return rest === 0 ? t('detail.minutes', { count: minutes }) : t('detail.minutesSeconds', { minutes, seconds: rest })
  }
}

/** What the caller can do: start, continue the attempt in progress, see their result, or nothing now. */
function Action({ publication }: { publication: PublicationView }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const { emailVerified } = useAuth()
  const start = useStartAttempt()
  const duration = useDuration()
  const mine = publication.myAttemptId
  const inProgress = mine && publication.myStatus === 'IN_PROGRESS' && minutesUntil(publication.myDeadline) > 0

  let action
  if (mine && !inProgress) {
    action = (
      <>
        <p className="text-muted-foreground text-sm" data-testid="detail-answered">{t('detail.answered')}</p>
        <Button asChild size="lg" variant="outline" className="w-full">
          <Link to={`/result/${mine}`} data-testid="detail-result">{t('detail.seeResult')}</Link>
        </Button>
      </>
    )
  } else if (!emailVerified) {
    action = (
      <Alert variant="warning" data-testid="detail-verify-first">
        <MailWarning aria-hidden />
        <AlertDescription className="text-current">{t('detail.verifyFirst')}</AlertDescription>
      </Alert>
    )
  } else if (inProgress || publication.canStart) {
    action = (
      <>
        <p className="text-muted-foreground text-sm" id="detail-start-hint">
          {inProgress ? t('detail.continueHint') : t('detail.startHint', { time: duration(publication.timeLimitSec) })}
        </p>
        <Button size="lg" className="w-full" disabled={start.isPending} aria-busy={start.isPending || undefined}
          aria-describedby="detail-start-hint" data-testid="detail-start"
          onClick={() => start.mutate(publication.publicationId)}>
          <Play aria-hidden />
          {inProgress
            ? t('detail.continue', { count: minutesUntil(publication.myDeadline) })
            : t('detail.start')}
        </Button>
      </>
    )
  } else {
    action = (
      <Button size="lg" className="w-full" disabled data-testid="detail-unavailable">{t('detail.unavailable')}</Button>
    )
  }
  return (
    <div className="flex flex-col gap-2">
      {action}
      <p className="text-muted-foreground text-sm empty:hidden" role="status" data-testid="detail-start-busy">
        {start.busy ? t('play.busy') : ''}
      </p>
      {start.isError && (
        <Alert variant="destructive" data-testid="detail-start-error">
          <AlertDescription>{playProblemText(start.error) || tQb('failed')}</AlertDescription>
        </Alert>
      )}
    </div>
  )
}

function Tiers({ tiers }: { tiers: Tier[] }) {
  const { t } = useTranslation(APP)
  if (tiers.length === 0) return null
  return (
    <Table data-testid="detail-tiers" aria-label={t('detail.tiers')}>
      <TableHeader>
        <TableRow>
          <TableHead scope="col">{t('detail.score')}</TableHead>
          <TableHead scope="col" className="text-right">{t('detail.reward')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {tiers.map((tier) => (
          <TableRow key={tier.seq}>
            <TableCell className="py-1.5">
              {tier.fromScore === tier.toScore
                ? t('detail.points', { count: tier.fromScore })
                : t('detail.pointRange', { from: tier.fromScore, to: tier.toScore })}
            </TableCell>
            <TableCell className="py-1.5 text-right font-medium">{formatKudos(tier.reward)}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

/** One fact of the row under the picture (Q34): an icon, the value, and what it is in small type under it. */
function Fact({ icon: Icon, label, value, testId }: { icon: LucideIcon; label: string; value: ReactNode; testId?: string }) {
  return (
    <div className="bg-card flex min-w-0 flex-col items-center gap-0.5 px-1 py-2 text-center" data-testid={testId}>
      {/* A list item holds its term and its definition only: the term comes first, shown under the value. */}
      <dt className="text-muted-foreground order-last w-full truncate text-[11px] leading-tight">{label}</dt>
      <dd className="flex w-full min-w-0 flex-col items-center gap-0.5">
        <Icon aria-hidden className="text-primary size-4" />
        <span className="w-full truncate text-sm font-semibold">{value}</span>
      </dd>
    </div>
  )
}

/** How high the folded text is: three lines of the body's type (`max-h-[4.5rem]`). */
const FOLDED_REM = 4.5

/**
 * Text that shows its first three lines and opens in place (Q34): the introduction of a quiz can be long, and the
 * start button should stay near. The button appears only when there is more to show; the content's own height is
 * watched (images and fonts that arrive later, a phone turned), never the clipped box's. A link below the fold that
 * gets the keyboard's focus opens the text, so nothing focused is out of sight.
 */
function Expandable({ children, testId }: { children: ReactNode; testId?: string }) {
  const { t } = useTranslation(APP)
  const id = useId()
  const inner = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const [more, setMore] = useState(false)
  useLayoutEffect(() => {
    const element = inner.current
    if (!element) return undefined
    const measure = () => {
      const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
      setMore(element.scrollHeight > FOLDED_REM * rem + 1)
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return undefined
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  return (
    <div className="flex flex-col gap-1" data-testid={testId}>
      <div id={id} className={cn('relative', !open && 'max-h-[4.5rem] overflow-hidden')}
        onFocusCapture={() => { if (more) setOpen(true) }}>
        <div ref={inner}>{children}</div>
        {!open && more && <span aria-hidden className="from-card absolute inset-x-0 bottom-0 h-6 bg-linear-to-t to-transparent" />}
      </div>
      {more && (
        <button type="button" className="text-primary inline-flex items-center gap-1 self-start text-sm font-medium"
          aria-expanded={open} aria-controls={id} onClick={() => setOpen(!open)}
          data-testid={testId && `${testId}-toggle`}>
          {open ? t('detail.readLess') : t('detail.readMore')}
          <ChevronDown aria-hidden className={cn('size-4 transition-transform', open && 'rotate-180')} />
        </button>
      )}
    </div>
  )
}

/** A block that opens and closes (the counts in full, the sponsor): the native disclosure, styled as the cards. */
function Disclosure({ summary, icon: Icon, testId, children }: {
  summary: ReactNode
  icon: LucideIcon
  testId?: string
  children: ReactNode
}) {
  return (
    <details className="group bg-card border shadow-xs" data-testid={testId}>
      <summary className="bg-muted flex cursor-pointer list-none items-center gap-2 px-3 py-2 text-sm group-open:border-b [&::-webkit-details-marker]:hidden">
        <Icon aria-hidden className="text-primary size-4 shrink-0" />
        <span className="min-w-0 flex-1">{summary}</span>
        <ChevronDown aria-hidden className="text-muted-foreground size-4 shrink-0 transition-transform group-open:rotate-180" />
      </summary>
      <div className="p-3">{children}</div>
    </details>
  )
}

/**
 * A publication's page (U-20 – U-23; templates `qb.app.publication` and `qb.app.publication-tiers`): the materials
 * under the title, the introduction, the facts and reward tiers, the completions, and the big start button. Opening
 * it records the first view (`QB_VIEW`) of a verified taker.
 */
export function PublicationPage({ publicationId, country }: { publicationId: string; country: string | null }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const { emailVerified } = useAuth()
  const duration = useDuration()
  const [at] = useState(() => new Date().toISOString())
  const request = { publicationId, at, country }
  const publication = useQuery({ queryKey: ['qb', 'publication', request, i18n.language],
    queryFn: () => readPublication(request) })
  const tiers = useQuery({ queryKey: ['qb', 'publication-tiers', request], queryFn: () => readTiers(request),
    enabled: !!publication.data })

  // The funnel's first view; a failure (a banned caller, a publication gone) changes nothing on the page.
  const [viewed, setViewed] = useState(false)
  useEffect(() => {
    if (emailVerified) recordView(publicationId).then(() => setViewed(true)).catch(() => undefined)
  }, [publicationId, emailVerified])
  // The view also claims an invitation addressed to the caller (Q25): a page the first read did not find is read once
  // more after it, and an invited publication is shown to the invitee from then on.
  const readAgain = useRef(false)
  const { data: found, fetchStatus, refetch } = publication
  useEffect(() => {
    if (viewed && !readAgain.current && found === null && fetchStatus === 'idle') {
      readAgain.current = true
      void refetch()
    }
  }, [viewed, found, fetchStatus, refetch])

  if (publication.isPending) return <PageState kind="loading" />
  if (publication.isError) {
    return <PageState kind="error" description={problemText(publication.error)}
      action={<Button variant="outline" onClick={() => void publication.refetch()}>{tQb('retry')}</Button>} />
  }
  const p = publication.data
  if (!p) {
    return <PageState kind="notFound" titleAs="h1" title={t('detail.notFound')}
      description={`${t('detail.notFoundHint')} ${t('detail.invitedHint')}`}
      action={<Button asChild variant="outline"><Link to="/">{t('detail.backHome')}</Link></Button>}
      data-testid="detail-not-found" />
  }
  const sponsor = p.sponsorCompany ?? p.sponsorName
  const survey = p.kind === 'SURVEY'
  const MethodIcon = METHOD_ICONS[p.rewardMethod]
  const prize = p.rewardMethod === 'OFFLINE_REDEEM' || p.rewardMethod === 'DIGITAL_PRIZE'
  const prizeText = p.rewardMethod === 'OFFLINE_REDEEM' ? p.redeemDescription
    : p.rewardMethod === 'DIGITAL_PRIZE' ? p.digitalDescription : null
  return (
    <article className="flex flex-col gap-3" aria-labelledby="detail-title" data-testid="detail">
      <Link to="/" className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1 self-start text-sm">
        <ArrowLeft aria-hidden className="size-4" />
        {t('detail.back')}
      </Link>
      {/* Q34: the picture leads, the name and the sponsor on its foot, what passing earns on its corner. */}
      <header className="relative -mx-4 aspect-video overflow-hidden sm:mx-0 sm:border" data-testid="detail-hero">
        {p.cover
          ? <FileImage fileId={p.cover} variant="w640" alt="" className="absolute inset-0 size-full" />
          : <CoverPlaceholder seed={p.publicationId} className="absolute inset-0 size-full" />}
        <span className="bg-primary text-primary-foreground absolute top-2 right-2 inline-flex items-center gap-1 px-2 py-1 text-xs font-semibold shadow-sm">
          <Trophy aria-hidden className="size-3.5" />
          {survey ? t('feed.surveyReward', { amount: formatKudos(p.maxReward) })
            : t('feed.upTo', { amount: formatKudos(p.maxReward) })}
        </span>
        <div className="from-qb-hero-from via-qb-hero-from/85 text-qb-hero-foreground absolute inset-x-0 bottom-0 flex flex-col gap-0.5 bg-linear-to-t to-transparent px-4 pt-10 pb-3">
          <h1 id="detail-title" className="text-xl leading-tight font-semibold" data-testid="detail-name">{p.name}</h1>
          {p.quizTitle && p.quizTitle !== p.name && (
            <p className="text-qb-hero-muted text-sm" data-testid="detail-quiz-title">{p.quizTitle}</p>
          )}
          {sponsor && (
            <p className="text-qb-hero-muted flex items-center gap-2 text-sm" data-testid="detail-sponsor">
              {p.sponsorAvatar && <FileImage fileId={p.sponsorAvatar} variant="w128" alt=""
                className="size-5 rounded-full" />}
              <span>{t('detail.by', { sponsor })}</span>
            </p>
          )}
        </div>
      </header>
      <dl className="bg-border grid grid-cols-3 gap-px border" data-testid="detail-facts" aria-label={t('detail.facts')}>
        <Fact icon={ListChecks} label={t('detail.questions')} value={p.questionCount} />
        {survey
          ? <Fact icon={BookOpen} label={t('detail.kind')} value={t('detail.survey')} />
          : <Fact icon={Target} label={t('detail.passScore')} value={`${p.passScore} / ${p.fullScore}`} />}
        <Fact icon={Timer} label={t('detail.timeLimit')} value={duration(p.timeLimitSec)} />
        <Fact icon={Trophy} label={survey ? t('detail.surveyReward') : prize ? t('detail.prizeValue') : t('detail.maxReward')}
          value={formatKudos(p.maxReward)} />
        <Fact icon={MethodIcon} label={t('detail.method')} testId="detail-method-fact"
          value={<span data-testid="detail-method" data-method={p.rewardMethod}>{t(`methods.${p.rewardMethod}Short`)}</span>} />
        <Fact icon={CalendarClock} label={t('detail.endAt')} value={formatDateTime(p.endAt)} />
      </dl>
      {p.audience === 'INVITED' && (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="detail-invited">
          <MailCheck aria-hidden className="size-4" />
          {t('detail.invitedOnly')}
        </p>
      )}
      <Materials materials={p.materials} />
      {p.intro && (
        <DetailCard title={t('detail.intro')} titleId="intro-title" icon={BookOpen} testId="detail-intro">
          <Expandable testId="detail-intro-text"><MarkdownView text={p.intro} /></Expandable>
        </DetailCard>
      )}
      <DetailCard title={t('detail.rewardTitle')} titleId="reward-title" icon={MethodIcon} testId="detail-reward">
        {/* Q22: what the sponsor does after a pass, in their words for a prize. */}
        <p className="text-sm" data-testid="detail-method-name">{t(`methods.${p.rewardMethod}`)}</p>
        {p.rewardMethod !== 'PLATFORM' && (
          <div className="bg-accent text-accent-foreground flex flex-col gap-1 px-3 py-2 text-sm"
            data-testid="detail-method-note" data-method={p.rewardMethod}>
            <span>{t(`methods.${p.rewardMethod}Hint`)}</span>
            {prizeText && <span className="font-medium whitespace-pre-line" data-testid="detail-prize">{prizeText}</span>}
          </div>
        )}
        {survey && <p className="text-muted-foreground text-sm" data-testid="detail-survey">{t('detail.surveyHint')}</p>}
        {!survey && <Tiers tiers={tiers.data ?? []} />}
      </DetailCard>
      <Disclosure icon={TrendingUp} testId="detail-stats" summary={
        <span data-testid="detail-stats-line">{t('detail.statsLine', { recent: p.last24h, total: p.completions })}</span>}>
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
          {([['last1h', p.last1h], ['last24h', p.last24h], ['last7d', p.last7d], ['last30d', p.last30d],
            ['total', p.completions]] as const).map(([key, value]) => (
            <div key={key} className="flex justify-between gap-2">
              <dt className="text-muted-foreground">{t(`detail.${key}`)}</dt>
              <dd className="font-medium">{value}</dd>
            </div>
          ))}
        </dl>
        {p.statsAt && (
          <p className="text-muted-foreground mt-2 text-xs">{t('detail.statsAt', { time: formatDateTime(p.statsAt) })}</p>
        )}
      </Disclosure>
      {p.sponsorIntro && (
        <Disclosure icon={Store} testId="detail-sponsor-intro" summary={t('detail.aboutSponsor')}>
          <p className="text-sm whitespace-pre-line">{p.sponsorIntro}</p>
        </Disclosure>
      )}
      <div className="bg-background sticky bottom-20 -mx-4 border-t px-4 py-3">
        <Action publication={p} />
      </div>
    </article>
  )
}
