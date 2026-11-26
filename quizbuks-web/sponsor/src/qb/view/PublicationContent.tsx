import { formatDateTime } from '@jabiz/client'
import { MarkdownView, PageTabs, Table, TableBody, TableCell, TableHead, TableHeader, TableRow, cn,
  type PageTab, type PageTabLinkProps } from '@jabiz/ui'
import { CircleCheck, ChevronDown, ClipboardList, Clock, ListChecks, MailCheck, Network, Ticket, type LucideIcon } from 'lucide-react'
import { useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { FileImage } from '../FileImage'
import { pickText } from '../legal'
import { QB } from '../messages'
import { sponsorPagePath } from '../page'
import { Link } from '../router'
import { CoverPlaceholder } from '../Illustrations'
import { formatKudos } from '../kudos'
import type { PublicationView, Tier } from '../play'
import { REWARD_METHOD_ICONS } from './FeedCard'
import { leadVideo, Materials } from './Materials'
import { QBVIEW } from './messages'
import { VideoPlayer } from './VideoPlayer'
import { useVideoFailed } from '../videoFailures'
import { postAge } from './postAge'

/** The time limit as people read it: minutes, with seconds when not whole. */
export function useDuration() {
  const { t } = useTranslation(QBVIEW)
  return (seconds: number) => {
    const minutes = Math.floor(seconds / 60)
    const rest = seconds % 60
    if (minutes === 0) return t('detail.seconds', { count: rest })
    return rest === 0 ? t('detail.minutes', { count: minutes }) : t('detail.minutesSeconds', { minutes, seconds: rest })
  }
}

function Tiers({ tiers }: { tiers: Tier[] }) {
  const { t } = useTranslation(QBVIEW)
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

/**
 * One fact of the row between the two dividers (Q113 §9.1 "详情页": x.com's row of actions; P8: the few that matter):
 * an icon (22 px) and the value, which says what it is; the term for assistive technology.
 */
function Fact({ icon: Icon, label, value, testId, className }: { icon: LucideIcon; label: string; value: ReactNode
  testId?: string; className?: string }) {
  return (
    <div className={cn('flex min-w-0 items-center gap-1', className)} data-testid={testId}>
      <dt className="sr-only">{label}</dt>
      <dd className="flex min-w-0 items-center gap-1">
        <Icon aria-hidden className="size-[22px] shrink-0" strokeWidth={1.7} />
        <span className="truncate">{value}</span>
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
  const { t } = useTranslation(QBVIEW)
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
        {!open && more && <span aria-hidden className="from-background absolute inset-x-0 bottom-0 h-6 bg-linear-to-t to-transparent" />}
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

/**
 * The page's two tabs (Q113 §9.3 "普通模式": the prototype's 资料 and 规则). What the tabs of before held is in the
 * body (the introduction; the completions in the line under the video) or under the rules (the reward).
 */
export const CONTENT_TABS = ['materials', 'rules'] as const
export type ContentTab = (typeof CONTENT_TABS)[number]

/** Q113b-2: the tabs of F-F4c an address may still name (`?tab=about`, `?tab=reward`), and where their content is. */
export const FORMER_TABS: Readonly<Record<string, ContentTab>> = { about: 'materials', reward: 'rules' }

/**
 * The page's tabs (F-F3c; the sponsor console's preview uses them as the App does): a link is what the App renders (an
 * address `?tab=`), a button what the preview renders (no address of its own).
 */
export interface ContentTabs {
  current: ContentTab
  /** Where a tab leads (the App's address; the preview's buttons ignore it). */
  path: (tab: ContentTab) => string
  renderLink: (tab: PageTab, props: PageTabLinkProps) => ReactNode
  /**
   * More under a tab, after its own content (what only the App has): `materials` the products and the Sponsor's page,
   * `reward` the referral panel at the end of the reward, `rules` the official rules, reporting and blocking.
   */
  extra?: Partial<Record<ContentTab | 'reward', ReactNode>>
}

/** "Jan 2", "Jan 2, 2027": a day as the post's line says it (no time: P8). */
function shortDate(time: string, language: string): string {
  const date = new Date(time)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat(language, { month: 'short', day: 'numeric',
    ...(date.getFullYear() !== new Date().getFullYear() ? { year: 'numeric' } : {}) }).format(date)
}

/**
 * What a taker reads of a publication on its page (Q113b-2: the normal mode of Q113 §9.3, as x.com's post detail;
 * Q34b: the sponsor console's preview draws the same from the Sponsor's own data). From the top: the author row (the
 * Sponsor's picture 40 px, name and role, and a near-black "Page" pill to their page); the name in bold and the
 * introduction at 17 px; the video (or the cover) with 16 px corners; a line of when it went up, how many answered
 * and when it ends; a row of four facts between two dividers (the reward in its colour, the questions, the time
 * limit, the pass mark); then the tabs: the materials, and the rules with the reward under them. The start bar is the
 * App's (the page's foot). Without `tabs` the two parts follow one another.
 */
export function PublicationContent({ publication: p, tiers, titleAs: Title = 'h1', draft = false,
  linkSponsor = false, autoplay = false, video, tabs }: {
  publication: PublicationView
  tiers: Tier[]
  /** The name's heading: the page's own on the taker's page, under the preview's heading in the console. */
  titleAs?: 'h1' | 'h2'
  /** A quiz before it is published (the console's preview): what only a publication sets reads "–". */
  draft?: boolean
  /** Q60a: whether the sponsor's name links to their published page (the app's own route; not the console's). */
  linkSponsor?: boolean
  /** Q63: whether the video leading the page starts by itself (the app's start page; not the console's preview). */
  autoplay?: boolean
  /** Q65: the app's own place for the leading video (its one player, carried over from the home's card). */
  video?: (player: string, title: string) => ReactNode
  /** The tabs; without them the materials and the rules follow one another. */
  tabs?: ContentTabs
}) {
  const { t } = useTranslation(QBVIEW)
  const { i18n } = useTranslation(QB)
  const duration = useDuration()
  const sponsor = p.sponsorCompany ?? p.sponsorName
  const nodeName = p.nodeName ? pickText(p.nodeName, i18n.language).text : ''
  const survey = p.kind === 'SURVEY'
  const MethodIcon = REWARD_METHOD_ICONS[p.rewardMethod]
  const prizeText = p.rewardMethod === 'OFFLINE_REDEEM' ? p.redeemDescription
    : p.rewardMethod === 'DIGITAL_PRIZE' ? p.digitalDescription : null
  const found = leadVideo(p.materials, p.video ?? null)
  // F-S15: a video file that failed to play does not lead the page: the cover does, the file stays in the materials.
  const leadFailed = useVideoFailed(found?.player)
  const lead = leadFailed ? null : found
  const leadTitle = lead?.material?.title ?? t('feed.videoTitle', { name: p.name })
  const materials = p.materials.filter((m) => m.no !== lead?.material?.no)
  const extra = tabs?.extra ?? {}

  // The author row: x.com's, with the Sponsor's page where x.com has "Follow" (Q113 §9.1 "详情页").
  const pagePath = p.sponsorHandle && linkSponsor && !draft ? sponsorPagePath(p.sponsorHandle) : null
  const initial = (sponsor ?? p.name).trim().charAt(0).toUpperCase()
  const avatar = p.sponsorAvatar
    ? <FileImage fileId={p.sponsorAvatar} variant="w128" alt="" className="size-10 rounded-full" />
    : <span aria-hidden className="bg-accent text-accent-foreground flex size-10 items-center justify-center rounded-full text-sm font-bold">{initial}</span>
  const author = sponsor && (
    <div className="flex items-center gap-2" data-testid="detail-author">
      {pagePath
        ? <Link to={pagePath} className="shrink-0 rounded-full" aria-label={t('detail.sponsorHomeOf', { name: sponsor })}>
          {avatar}</Link>
        : <span className="shrink-0">{avatar}</span>}
      <span className="flex min-w-0 flex-1 flex-col text-[15px] leading-5">
        <span className="truncate font-bold" data-testid="detail-sponsor">{sponsor}</span>
        <span className="text-muted-foreground">{t('detail.sponsorRole')}</span>
      </span>
      {pagePath && (
        <Link to={pagePath} data-testid="detail-sponsor-page" aria-label={t('detail.sponsorHomeOf', { name: sponsor })}
          className="bg-foreground text-background focus-visible:ring-ring flex h-8 shrink-0 items-center rounded-full px-4 text-sm font-bold outline-none hover:opacity-90 focus-visible:ring-2 focus-visible:ring-offset-2">
          {t('detail.sponsorHome')}
        </Link>
      )}
    </div>
  )

  // The line under the video: when it went up, how many answered, when it ends (x.com's line of time and counts).
  const age = draft ? null : postAge(p.startAt, new Date())
  const published = age && ('unit' in age ? t(`detail.publishedAgo.${age.unit}`, { count: age.count })
    : t('detail.publishedAgo.date', { date: shortDate(p.startAt, i18n.language) }))
  const line = (
    <p className="text-muted-foreground mt-1 mb-1 text-[15px] leading-5" data-testid="detail-published">
      {published && <>{published}<span aria-hidden> · </span></>}
      <b className="text-foreground tabular-nums" data-testid="detail-completions">{p.completions.toLocaleString()}</b>
      {' '}{t('detail.answeredCount')}
      {!draft && <><span aria-hidden> · </span>{t('detail.endsOn', { date: shortDate(p.endAt, i18n.language) })}</>}
    </p>
  )

  const rules: [string, ReactNode, string?][] = [
    [survey ? t('detail.kind') : t('detail.questions'), p.pickCount != null
      ? t('detail.pickCount', { count: p.pickCount, total: p.questionCount })
      : t('detail.questionCount', { count: p.questionCount })],
    ...(survey ? [] : [[t('detail.passScore'), t('detail.passOf', { pass: draft ? '–' : p.passScore, full: p.fullScore })] as [string, ReactNode]]),
    [t('detail.timeLimit'), duration(p.timeLimitSec)],
    ...(p.entryFee ? [[t('detail.entryFee'), formatKudos(p.entryFee)] as [string, ReactNode]] : []),
    [t('detail.endAt'), draft ? '–' : t('detail.until', { time: formatDateTime(p.endAt) })],
    ...(nodeName ? [[t('detail.node'), t(p.nodeLit ? 'detail.nodeLit' : 'detail.nodeOpen', { name: nodeName })] as [string, ReactNode]] : []),
  ]
  const rulesPart = (
    <>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-[15px]" data-testid="detail-rules">
        {rules.map(([label, value, testId]) => (
          <div key={label} className="contents" data-testid={testId}>
            <dt className="text-muted-foreground">{label}</dt>
            <dd className="font-medium">{value}</dd>
          </div>
        ))}
      </dl>
      {/* The reward, what the tab "Reward" had: how it is paid, the prize in the Sponsor's words, the tiers. */}
      {!draft && (
        <section aria-labelledby="detail-reward-title" className="flex flex-col gap-2" data-testid="detail-reward">
          <h2 id="detail-reward-title" className="text-[17px] font-bold">{t('detail.rewardSection')}</h2>
          <p className="font-medium" data-testid="detail-reward-line">
            {t('detail.rewardLine', { method: t(`methods.${p.rewardMethod}Short`) })}
          </p>
          {/* Q22: what the sponsor does after a pass, in their words for a prize. */}
          <p className="text-sm" data-testid="detail-method-name">{t(`methods.${p.rewardMethod}`)}</p>
          {p.rewardMethod !== 'PLATFORM' && (
            <div className="bg-accent text-accent-foreground flex flex-col gap-1 rounded-[16px] px-3 py-2 text-sm"
              data-testid="detail-method-note" data-method={p.rewardMethod}>
              <span>{t(`methods.${p.rewardMethod}Hint`)}</span>
              {prizeText && <span className="font-medium whitespace-pre-line" data-testid="detail-prize">{prizeText}</span>}
              {/* Q89b-1: another sponsor's benefit is collected at its provider's place. */}
              {p.rewardMethod === 'OFFLINE_REDEEM' && p.fulfillerCompany && (
                <span data-testid="detail-fulfiller">{t('detail.collectAt', { company: p.fulfillerCompany })}</span>
              )}
            </div>
          )}
          {survey && <p className="text-muted-foreground text-sm" data-testid="detail-survey">{t('detail.surveyHint')}</p>}
          {!survey && <Tiers tiers={tiers} />}
          {extra.reward}
        </section>
      )}
      {extra.rules}
    </>
  )
  const materialsPart = (
    <>
      <Materials materials={p.materials} exclude={lead?.material?.no} />
      {materials.length === 0 && !extra.materials && (
        <p className="text-muted-foreground text-[15px]" data-testid="detail-no-materials">{t('detail.noMaterials')}</p>
      )}
      {extra.materials}
    </>
  )
  const current = tabs?.current ?? 'materials'

  return (
    <>
      {author}
      {/* The text of the post: the name in bold, then the introduction (its first lines, the rest on demand). */}
      <div className="flex flex-col text-[17px] leading-6">
        <Title id="detail-title" className="text-[17px] leading-6 font-bold" data-testid="detail-name">{p.name}</Title>
        {p.quizTitle && p.quizTitle !== p.name && (
          <p className="text-muted-foreground text-[15px] leading-5" data-testid="detail-quiz-title">{p.quizTitle}</p>
        )}
        {p.intro && (
          <section aria-label={t('detail.intro')} className="mt-1 [&_.markdown-view]:text-[17px] [&_.markdown-view]:leading-6"
            data-testid="detail-intro">
            <Expandable testId="detail-intro-text"><MarkdownView text={p.intro} /></Expandable>
          </section>
        )}
      </div>
      {/* Q63: a video to learn from before answering, started (muted) as the page opens; else the cover. */}
      <div className="border-qb-outline bg-muted relative aspect-video w-full overflow-hidden rounded-[16px] border"
        data-testid="detail-hero" data-video={lead ? true : undefined}>
        {lead
          ? video ? video(lead.player, leadTitle)
            : <VideoPlayer player={lead.player} autoplay={autoplay} title={leadTitle} className="aspect-video w-full" />
          : p.cover
            ? <FileImage fileId={p.cover} variant="w640" alt="" className="absolute inset-0 size-full object-cover" />
            : <CoverPlaceholder seed={p.publicationId} className="absolute inset-0 size-full" />}
      </div>
      {line}
      {/* The row between the two dividers: the four facts that matter before starting (P8). */}
      <dl className="text-muted-foreground flex h-12 items-center justify-between gap-2 border-y text-[13px]"
        data-testid="detail-facts" aria-label={t('detail.facts')}>
        <Fact icon={MethodIcon} className="text-qb-reward-foreground font-bold" testId="detail-method-fact"
          label={survey ? t('detail.surveyReward') : p.rewardMethod === 'PLATFORM' ? t('detail.maxReward') : t('detail.prizeValue')}
          value={draft ? '–' : <span data-testid="detail-method" data-method={p.rewardMethod}>
            {survey ? t('feed.surveyReward', { amount: formatKudos(p.maxReward) })
              : t('feed.upTo', { amount: formatKudos(p.maxReward) })}
            <span className="sr-only"> · {t(`methods.${p.rewardMethod}Short`)}</span>
          </span>} />
        <Fact icon={ListChecks} label={t('detail.questions')}
          value={t('detail.questionCount', { count: p.pickCount ?? p.questionCount })} />
        <Fact icon={Clock} label={t('detail.timeLimit')} value={duration(p.timeLimitSec)} />
        {survey
          ? <Fact icon={ClipboardList} label={t('detail.kind')} value={t('detail.survey')} />
          : <Fact icon={CircleCheck} label={t('detail.passScore')}
            value={draft ? '–' : t('detail.passMark', { pass: p.passScore })} />}
      </dl>
      {/* What matters too before starting, when a publication has it (shown above the tabs, as with the facts): the
          entry fee (Q88b: paid once from the transferable Kudos, not refunded) and the node passing lights (F-S2d). */}
      {(p.entryFee || nodeName) ? (
        <dl className="text-muted-foreground -mt-1 flex flex-wrap gap-x-4 gap-y-1 text-[13px]" data-testid="detail-more-facts">
          {p.entryFee ? <Fact icon={Ticket} label={t('detail.entryFee')} testId="detail-entry-fee"
            value={<span className="text-foreground font-bold">{formatKudos(p.entryFee)}</span>} /> : null}
          {nodeName ? <Fact icon={Network} label={t('detail.node')} testId="detail-node"
            value={<span data-testid="detail-node-state" data-lit={p.nodeLit ? 'true' : 'false'}>
              {t(p.nodeLit ? 'detail.nodeLit' : 'detail.nodeOpen', { name: nodeName })}</span>} /> : null}
        </dl>
      ) : null}
      {p.audience === 'INVITED' && (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="detail-invited">
          <MailCheck aria-hidden className="size-4" />
          {t('detail.invitedOnly')}
        </p>
      )}
      {tabs ? (
        <div className="-mx-4 flex flex-col" data-testid="detail-tabs" data-tab={current}>
          <PageTabs label={t('detail.tabs.label')} current={current} indicator="label" fill className="border-b"
            tabs={CONTENT_TABS.map((tab) => ({ key: tab, path: tabs.path(tab),
              label: tab === 'materials' ? t('detail.materialsCount', { count: materials.length }) : t(`detail.tabs.${tab}`) }))}
            renderLink={tabs.renderLink} />
          <div className="flex flex-col gap-3 px-4 pt-3" data-testid={`detail-tab-${current}`}>
            {current === 'materials' ? materialsPart : rulesPart}
          </div>
        </div>
      ) : (
        <>
          {materialsPart}
          {rulesPart}
        </>
      )}
    </>
  )
}
