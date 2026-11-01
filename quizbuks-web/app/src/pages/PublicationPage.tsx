import { readPublicationProducts } from '../share/api'
import { quizProducts } from '../qb/products'
import { countProductClick } from '../share/counting'
import { ProductCards } from '../qb/view/ProductCards'
import { QBVIEW } from '../qb/view/messages'
import { formatDateTime, i18n, useAuth } from '@jabiz/client'
import { Alert, AlertDescription, Button, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { BadgeCheck, MailWarning, Play, UserRoundSearch } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { playProblemText } from '../play/busy'
import { useStartAttempt } from '../play/useStartAttempt'
import { ACCOUNT_DETAILS_PATH } from './AccountPage'
import { QB } from '../qb/messages'
import { readPublication, readTiers, recordView, type PublicationView } from '../qb/play'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { prefersStillVideo } from '../qb/video'
import { SponsorVisit } from './SponsorVisit'
import { VideoSlot } from '../video/stage'
import { PublicationContent, useDuration } from '../qb/view/PublicationContent'

/** Whole minutes from now until a time, at least 0 (by the device's clock: a hint only). */
export function minutesUntil(time: string | null, now: number = Date.now()): number {
  const end = time ? Date.parse(time) : NaN
  return Number.isFinite(end) ? Math.max(0, Math.ceil((end - now) / 60_000)) : 0
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
  } else if (publication.heldUntil) {
    // Q70a (option B): a certification's round, and the caller holds the certification.
    action = (
      <Alert data-testid="detail-certification-held">
        <BadgeCheck aria-hidden />
        <AlertDescription className="text-current">
          {t('detail.certificationHeld', { time: formatDateTime(publication.heldUntil) })}
        </AlertDescription>
      </Alert>
    )
  } else if (!publication.matched) {
    // Q47: aimed at people the caller's profile does not (yet) say they are.
    action = (
      <Alert data-testid="detail-not-matched">
        <UserRoundSearch aria-hidden />
        <AlertDescription className="text-current flex flex-col items-start gap-2">
          <span>{t('detail.notMatched')}</span>
          <Link to={ACCOUNT_DETAILS_PATH} className="text-primary underline" data-testid="detail-complete-profile">
            {t('detail.completeProfile')}
          </Link>
        </AlertDescription>
      </Alert>
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

/**
 * A publication's page (U-20 – U-23; templates `qb.app.publication` and `qb.app.publication-tiers`): the materials
 * under the title, the introduction, the facts and reward tiers, the completions, and the big start button. Opening
 * it records the first view (`QB_VIEW`) of a verified taker.
 */
export function PublicationPage({ publicationId, country }: { publicationId: string; country: string | null }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const { emailVerified } = useAuth()
  const [at] = useState(() => new Date().toISOString())
  const request = { publicationId, at, country }
  const publication = useQuery({ queryKey: ['qb', 'publication', request, i18n.language],
    queryFn: () => readPublication(request) })
  const tiers = useQuery({ queryKey: ['qb', 'publication-tiers', request], queryFn: () => readTiers(request),
    enabled: !!publication.data })
  // Q60b: the products the sponsor shows with the quiz (question 0); quietly none when they cannot be read.
  const products = useQuery({ queryKey: ['qb', 'publication-products', publicationId],
    queryFn: () => readPublicationProducts(publicationId), enabled: !!publication.data, retry: false })
  const { t: tView } = useTranslation(QBVIEW)

  // The funnel's first view; a failure (a banned caller, a publication gone) changes nothing on the page.
  const [viewed, setViewed] = useState<string | null>(null)
  // The view also claims an invitation addressed to the caller (Q25): a page the first read did not find is read once
  // more after it, and until then "not available" is not shown, so an invitee never sees it flash before the quiz.
  const [settledFor, setSettledFor] = useState<string | null>(null)
  useEffect(() => {
    if (emailVerified) {
      recordView(publicationId).then(() => setViewed(publicationId)).catch(() => setSettledFor(publicationId))
    }
  }, [publicationId, emailVerified])
  const readAgain = useRef(false)
  const { data: found, fetchStatus, refetch } = publication
  useEffect(() => {
    if (viewed === publicationId && !readAgain.current && found === null && fetchStatus === 'idle') {
      readAgain.current = true
      void refetch().finally(() => setSettledFor(publicationId))
    }
  }, [viewed, publicationId, found, fetchStatus, refetch])
  const claiming = emailVerified && settledFor !== publicationId

  if (publication.isPending || (publication.data === null && claiming)) return <PageState kind="loading" />
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
  return (
    <article className="flex flex-col gap-3" aria-labelledby="detail-title" data-testid="detail">
      {/* Q65: the home's player carries on here, its sound on from the tap that opened the page; a viewer who
          asked for less motion gets a player that waits for their own tap. */}
      <PublicationContent publication={p} tiers={tiers.data ?? []} linkSponsor autoplay={!prefersStillVideo()}
        video={prefersStillVideo() ? undefined : (player, title) => (
          <VideoSlot id={`page:${p.publicationId}`} player={player} title={title} mode="page"
            className="bg-muted relative aspect-video w-full sm:border" />
        )} />
      <ProductCards products={quizProducts(products.data)}
        title={tView('products.title')} testId="publication-products" onOpen={countProductClick} />
      {/* Q64: the sponsor's own page, with all their products, from the start page as from the result. */}
      <SponsorVisit handle={p.sponsorHandle} sponsor={p.sponsorCompany ?? p.sponsorName} avatar={p.sponsorAvatar}
        products={quizProducts(products.data).length > 0} />
      <div className="bg-background sticky bottom-20 -mx-4 border-t px-4 py-3">
        <Action publication={p} />
      </div>
    </article>
  )
}
