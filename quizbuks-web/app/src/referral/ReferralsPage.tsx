import { formatDateTime } from '@jabiz/client'
import { Alert, AlertDescription, Badge, Button, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { UserRoundPlus } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { sharePath } from '../share/share'
import { CashReward, DigitalPrize, Redemption, type Outcome } from '../pages/ResultPage'
import { TitleBadge } from '../pages/TitleBadge'
import { LinkCard, useReferralReward } from './ReferralPanel'
import { readMyLinks, readMyReferrals, readReferral, REFERRALS_QUERY, referralQueryKey, type ReferralStatus } from './api'

const STATUS_VARIANT: Record<ReferralStatus, 'default' | 'secondary' | 'outline'> = {
  REFERRED: 'secondary', REWARDED: 'default', EXPIRED: 'outline', VOID: 'outline',
}

/**
 * The referrer's referrals (Q70): their links with the places left, and each friend who came, by quiz and state,
 * never by name; a rewarded one opens its page (the cash to confirm, the code of a prize, a digital prize).
 */
export function ReferralsPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const reward = useReferralReward()
  const links = useQuery({ queryKey: [...REFERRALS_QUERY, 'links'], queryFn: readMyLinks })
  const referrals = useQuery({ queryKey: [...REFERRALS_QUERY, 'list'], queryFn: readMyReferrals })
  if (links.isPending || referrals.isPending) return <PageState kind="loading" />
  if (links.isError || referrals.isError) {
    return <PageState kind="error" description={problemText(links.error ?? referrals.error)}
      action={<Button variant="outline" onClick={() => {
        void links.refetch()
        void referrals.refetch()
      }}>{tQb('retry')}</Button>} />
  }
  return (
    <div className="flex flex-col gap-4" data-testid="referrals">
      <h1 className="flex items-center gap-2 text-2xl font-semibold">
        <TitleBadge icon={UserRoundPlus} />
        {t('referral.page.title')}
      </h1>
      <p className="text-muted-foreground text-sm">{t('referral.page.intro')}</p>
      <section aria-labelledby="referral-links-title" className="flex flex-col gap-2">
        <h2 id="referral-links-title" className="text-lg font-semibold">{t('referral.page.links')}</h2>
        {links.data.length === 0 ? (
          <p className="text-muted-foreground text-sm" data-testid="referral-links-empty">{t('referral.page.noLinks')}</p>
        ) : (
          <ul className="flex flex-col gap-3">
            {links.data.map((link) => (
              <li key={link.linkKey} className="flex flex-col gap-2 rounded-lg border p-3" data-testid="referral-link-row">
                <Link to={sharePath(link.publicationId)} className="font-medium underline-offset-4 hover:underline">
                  {link.publicationName}
                </Link>
                {link.referral && <span className="text-sm">{t('referral.offer', { reward: reward(link.referral),
                  days: link.referral.days })}</span>}
                <span className="text-muted-foreground text-xs">{t('referral.page.brought', { count: link.broughtByMe })}</span>
                {link.status === 'LIVE' && link.placesLeft > 0 ? (
                  <LinkCard link={{ publicationId: link.publicationId, linkKey: link.linkKey,
                    quotaLeft: link.placesLeft }} publicationName={link.publicationName} />
                ) : (
                  <span className="text-muted-foreground text-xs">{t('referral.page.closed')}</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
      <section aria-labelledby="referral-list-title" className="flex flex-col gap-2">
        <h2 id="referral-list-title" className="text-lg font-semibold">{t('referral.page.friends')}</h2>
        {referrals.data.length === 0 ? (
          <p className="text-muted-foreground text-sm" data-testid="referrals-empty">{t('referral.page.noFriends')}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {referrals.data.map((r) => (
              <li key={r.referralId} className="flex flex-col gap-1 rounded-lg border p-3" data-testid="referral-row"
                data-status={r.status}>
                <span className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{r.publicationName}</span>
                  <Badge variant={STATUS_VARIANT[r.status]}>{t(`referral.status.${r.status}`)}</Badge>
                </span>
                <span className="text-muted-foreground text-xs">
                  {r.status === 'REFERRED'
                    ? t('referral.page.until', { time: formatDateTime(r.expiresAt) })
                    : formatDateTime(r.rewardedAt ?? r.endedAt ?? r.referredAt)}
                </span>
                {r.status === 'REWARDED' && (
                  <Link to={`/referral/${encodeURIComponent(r.referralId)}`} className="text-primary w-fit text-sm underline">
                    {r.rewardMethod === 'PLATFORM' ? formatKudos(r.amount) : t('referral.page.seeReward')}
                  </Link>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

/** One rewarded referral (Q70): its reward as a result shows it, through the same parts. */
export function ReferralPage({ referralId }: { referralId: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const view = useQuery({ queryKey: referralQueryKey(referralId), queryFn: () => readReferral(referralId) })
  if (view.isPending) return <PageState kind="loading" />
  if (view.isError) {
    return <PageState kind="error" description={problemText(view.error)}
      action={<Button variant="outline" onClick={() => void view.refetch()}>{tQb('retry')}</Button>} />
  }
  const r = view.data
  const outcome: Outcome = {
    status: 'SUBMITTED', score: 0, fullScore: 0, passScore: null, passed: true, reward: r.amount,
    rewardStatus: r.rewardMethod === 'SPONSOR_CASH' ? 'CASH' : r.rewardMethod === 'OFFLINE_REDEEM' ? 'REDEEM'
      : r.rewardMethod === 'DIGITAL_PRIZE' ? 'DIGITAL' : 'PAID',
    message: null, questions: [], cash: r.cash, redeem: r.redeem, digital: r.digital,
  }
  return (
    <article className="flex flex-col gap-3" data-testid="referral-page">
      <h1 className="text-2xl font-semibold">{t('referral.page.reward')}</h1>
      <p className="text-muted-foreground text-sm">{t('referral.page.rewardFor', { name: r.publicationName ?? '' })}</p>
      {r.status !== 'REWARDED' ? (
        <Alert><AlertDescription>{t(`referral.status.${r.status}`)}</AlertDescription></Alert>
      ) : r.rewardMethod === 'SPONSOR_CASH' ? (
        <CashReward cash={r.cash} attemptId={referralId} outcome={outcome} />
      ) : r.rewardMethod === 'OFFLINE_REDEEM' ? (
        <Redemption redeem={r.redeem} outcome={outcome} />
      ) : r.rewardMethod === 'DIGITAL_PRIZE' ? (
        <DigitalPrize digital={r.digital} attemptId={referralId} outcome={outcome} />
      ) : (
        <Alert variant="success" data-testid="referral-paid">
          <AlertDescription>{t('referral.page.paid', { amount: formatKudos(r.amount) })}</AlertDescription>
        </Alert>
      )}
    </article>
  )
}
