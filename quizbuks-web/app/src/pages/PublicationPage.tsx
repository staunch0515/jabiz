import { formatDateTime, i18n, useAuth } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Button,
  DescriptionList,
  MarkdownView,
  PageState,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, MailCheck, MailWarning, Play } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
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
    <section aria-labelledby="tiers-title" className="flex flex-col gap-2">
      <h2 id="tiers-title" className="text-lg font-semibold">{t('detail.tiers')}</h2>
      <Table data-testid="detail-tiers">
        <TableHeader>
          <TableRow>
            <TableHead scope="col">{t('detail.score')}</TableHead>
            <TableHead scope="col" className="text-right">{t('detail.reward')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {tiers.map((tier) => (
            <TableRow key={tier.seq}>
              <TableCell>
                {tier.fromScore === tier.toScore
                  ? t('detail.points', { count: tier.fromScore })
                  : t('detail.pointRange', { from: tier.fromScore, to: tier.toScore })}
              </TableCell>
              <TableCell className="text-right font-medium">{formatKudos(tier.reward)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </section>
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
  return (
    <article className="flex flex-col gap-5" aria-labelledby="detail-title" data-testid="detail">
      <Link to="/" className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1 self-start text-sm">
        <ArrowLeft aria-hidden className="size-4" />
        {t('detail.back')}
      </Link>
      {p.cover && <FileImage fileId={p.cover} variant="w640" alt="" className="aspect-video w-full rounded-lg" />}
      <header className="flex flex-col gap-1">
        <h1 id="detail-title" className="text-xl font-semibold" data-testid="detail-name">{p.name}</h1>
        {p.quizTitle && p.quizTitle !== p.name && <p className="text-muted-foreground">{p.quizTitle}</p>}
        {sponsor && (
          <p className="flex items-center gap-2 text-sm" data-testid="detail-sponsor">
            {p.sponsorAvatar && <FileImage fileId={p.sponsorAvatar} variant="w128" alt=""
              className="size-6 rounded-full" />}
            <span>{t('detail.by', { sponsor })}</span>
          </p>
        )}
      </header>
      {p.audience === 'INVITED' && (
        <p className="text-muted-foreground flex items-center gap-2 text-sm" data-testid="detail-invited">
          <MailCheck aria-hidden className="size-4" />
          {t('detail.invitedOnly')}
        </p>
      )}
      <Materials materials={p.materials} />
      {p.intro && (
        <section aria-labelledby="intro-title" className="flex flex-col gap-2">
          <h2 id="intro-title" className="text-lg font-semibold">{t('detail.intro')}</h2>
          <MarkdownView text={p.intro} />
        </section>
      )}
      <section aria-labelledby="facts-title" className="flex flex-col gap-2">
        <h2 id="facts-title" className="text-lg font-semibold">{t('detail.facts')}</h2>
        {/* A survey (Q12) has no score: one reward for answering every question. */}
        <DescriptionList data-testid="detail-facts" items={survey ? [
          { key: 'kind', label: t('detail.kind'), value: t('detail.survey') },
          { key: 'questions', label: t('detail.questions'), value: p.questionCount },
          { key: 'timeLimit', label: t('detail.timeLimit'), value: duration(p.timeLimitSec) },
          { key: 'maxReward', label: t('detail.surveyReward'), value: formatKudos(p.maxReward) },
          { key: 'method', label: t('detail.method'),
            value: <span data-testid="detail-method" data-method={p.rewardMethod}>{t(`methods.${p.rewardMethod}`)}</span> },
          { key: 'endAt', label: t('detail.endAt'), value: formatDateTime(p.endAt) },
        ] : [
          { key: 'questions', label: t('detail.questions'), value: p.questionCount },
          { key: 'fullScore', label: t('detail.fullScore'), value: p.fullScore },
          { key: 'passScore', label: t('detail.passScore'), value: p.passScore },
          { key: 'timeLimit', label: t('detail.timeLimit'), value: duration(p.timeLimitSec) },
          { key: 'maxReward', label: p.rewardMethod === 'OFFLINE_REDEEM' || p.rewardMethod === 'DIGITAL_PRIZE'
            ? t('detail.prizeValue') : t('detail.maxReward'),
            value: formatKudos(p.maxReward) },
          { key: 'method', label: t('detail.method'),
            value: <span data-testid="detail-method" data-method={p.rewardMethod}>{t(`methods.${p.rewardMethod}`)}</span> },
          { key: 'endAt', label: t('detail.endAt'), value: formatDateTime(p.endAt) },
        ]} />
        {survey && <p className="text-muted-foreground text-sm" data-testid="detail-survey">{t('detail.surveyHint')}</p>}
        {/* Q22: what the sponsor does after a pass, in their words for a prize. */}
        {p.rewardMethod !== 'PLATFORM' && (
          <Alert data-testid="detail-method-note" data-method={p.rewardMethod}>
            <AlertDescription className="flex flex-col gap-1">
              <span>{t(`methods.${p.rewardMethod}Hint`)}</span>
              {p.rewardMethod === 'OFFLINE_REDEEM' && p.redeemDescription && (
                <span className="whitespace-pre-line font-medium" data-testid="detail-prize">{p.redeemDescription}</span>
              )}
              {p.rewardMethod === 'DIGITAL_PRIZE' && p.digitalDescription && (
                <span className="whitespace-pre-line font-medium" data-testid="detail-prize">{p.digitalDescription}</span>
              )}
            </AlertDescription>
          </Alert>
        )}
      </section>
      {!survey && <Tiers tiers={tiers.data ?? []} />}
      <section aria-labelledby="stats-title" className="flex flex-col gap-2">
        <h2 id="stats-title" className="text-lg font-semibold">{t('detail.completions')}</h2>
        <DescriptionList layout="vertical" columns={2} data-testid="detail-stats" items={[
          { key: 'last1h', label: t('detail.last1h'), value: p.last1h },
          { key: 'last24h', label: t('detail.last24h'), value: p.last24h },
          { key: 'last7d', label: t('detail.last7d'), value: p.last7d },
          { key: 'last30d', label: t('detail.last30d'), value: p.last30d },
          { key: 'total', label: t('detail.total'), value: p.completions },
        ]} />
        {p.statsAt && (
          <p className="text-muted-foreground text-xs">{t('detail.statsAt', { time: formatDateTime(p.statsAt) })}</p>
        )}
      </section>
      {p.sponsorIntro && (
        <section aria-labelledby="sponsor-title" className="flex flex-col gap-2">
          <h2 id="sponsor-title" className="text-lg font-semibold">{t('detail.aboutSponsor')}</h2>
          <p className="text-sm whitespace-pre-line">{p.sponsorIntro}</p>
        </section>
      )}
      <div className="bg-background sticky bottom-20 -mx-4 border-t px-4 py-3">
        <Action publication={p} />
      </div>
    </article>
  )
}
