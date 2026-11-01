import { formatDateTime } from '@jabiz/client'
import { Alert, AlertDescription, AlertTitle, Button, Input, notify } from '@jabiz/ui'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Copy, Handshake, Share2, UserRoundPlus } from 'lucide-react'
import QRCode from 'qrcode'
import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { formatKudos } from '../qb/kudos'
import type { PublicationView, ReferralPolicy } from '../qb/play'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { REFERRALS_PATH, REFERRALS_QUERY, referralLink, referralUrl, type ReferralLink } from './api'

/** What a referral earns, in words: Kudos, or the sponsor's cash or prize with what it is. */
export function useReferralReward() {
  const { t } = useTranslation(APP)
  return (policy: Pick<ReferralPolicy, 'method' | 'amount' | 'description'>) => {
    if (policy.method === 'PLATFORM') return formatKudos(policy.amount)
    if (policy.method === 'SPONSOR_CASH') return t('referral.reward.cash', { amount: formatKudos(policy.amount) })
    return policy.description ?? t(`referral.reward.${policy.method}`)
  }
}

/** The link as an SVG image URL for `<img>`, drawn on the device (the CSP allows `data:` images). */
async function qrImageUrl(link: string): Promise<string> {
  const svg = await QRCode.toString(link, { type: 'svg', margin: 2, errorCorrectionLevel: 'M' })
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

/** The referrer's link with its copy button and code: shown, never sent for them (D-Q70-19b). */
export function LinkCard({ link, publicationName }: { link: ReferralLink; publicationName: string }) {
  const { t } = useTranslation(APP)
  const url = referralUrl(link.publicationId, link.linkKey)
  const [qr, setQr] = useState<string | null>(null)
  const inputId = useId()
  useEffect(() => {
    let live = true
    void qrImageUrl(url).then((image) => live && setQr(image)).catch(() => undefined)
    return () => {
      live = false
    }
  }, [url])
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url)
      notify.success(t('referral.copied'))
    } catch {
      notify.error(t('referral.copyFailed'))
    }
  }
  const share = async () => {
    try {
      await navigator.share({ title: publicationName, url })
    } catch {
      // Cancelled, or not possible: the link is on the page to copy.
    }
  }
  return (
    <div className="flex flex-col gap-2" data-testid="referral-link">
      <label htmlFor={inputId} className="text-sm font-medium">{t('referral.yourLink')}</label>
      <div className="flex gap-2">
        <Input id={inputId} readOnly value={url} onFocus={(e) => e.currentTarget.select()} data-testid="referral-url" />
        <Button variant="outline" size="icon" onClick={() => void copy()} aria-label={t('referral.copy')}
          data-testid="referral-copy">
          <Copy aria-hidden />
        </Button>
        {typeof navigator.share === 'function' && (
          <Button variant="outline" size="icon" onClick={() => void share()} aria-label={t('referral.share')}>
            <Share2 aria-hidden />
          </Button>
        )}
      </div>
      {qr && <img src={qr} alt={t('referral.qr', { name: publicationName })} className="size-40 self-center"
        data-testid="referral-qr" />}
      <p className="text-muted-foreground text-xs">{t('referral.placesLeft', { count: link.quotaLeft })}</p>
      <p className="text-muted-foreground text-xs">{t('referral.friendsOnly')}</p>
    </div>
  )
}

/**
 * "Referrals welcome" on a publication's page (Q70): to a referee, that a friend referred them and until when a pass
 * counts; to everybody else, what a friend's pass earns and the link to send (made on request; the server checks the
 * referrer certification and the publication's requirement, and says why not).
 */
export function ReferralPanel({ publication }: { publication: PublicationView }) {
  const { t } = useTranslation(APP)
  const reward = useReferralReward()
  const queryClient = useQueryClient()
  const make = useMutation({
    mutationFn: () => referralLink(publication.publicationId),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: REFERRALS_QUERY }),
  })
  const policy = publication.referral
  const referred = publication.myReferralStatus === 'REFERRED' && !publication.myAttemptId
    || publication.myReferralStatus === 'REFERRED' && publication.myStatus === 'IN_PROGRESS'
  if (!policy && !referred) return null
  return (
    <section className="flex flex-col gap-2" aria-labelledby="referral-title" data-testid="referral-panel">
      {referred && publication.myReferralExpiresAt && (
        <Alert data-testid="referral-referred">
          <Handshake aria-hidden />
          <AlertDescription className="text-current">
            {t('referral.referred', { time: formatDateTime(publication.myReferralExpiresAt) })}
          </AlertDescription>
        </Alert>
      )}
      {policy && (
        <Alert>
          <UserRoundPlus aria-hidden />
          <AlertTitle id="referral-title">{t('referral.title')}</AlertTitle>
          <AlertDescription className="text-current flex flex-col gap-2">
            <span data-testid="referral-offer">{t('referral.offer', { reward: reward(policy), days: policy.days })}</span>
            {policy.referrerRequires !== 'NONE' && (
              <span className="text-xs">{t(`referral.requires.${policy.referrerRequires}`)}</span>
            )}
            {make.data ? (
              <LinkCard link={make.data} publicationName={publication.name} />
            ) : (
              <Button variant="outline" className="w-fit" disabled={make.isPending} aria-busy={make.isPending || undefined}
                onClick={() => make.mutate()} data-testid="referral-make">
                {t('referral.make')}
              </Button>
            )}
            {make.isError && <span className="text-destructive" role="alert" data-testid="referral-error">
              {problemText(make.error)}</span>}
            <Link to={REFERRALS_PATH} className="text-primary w-fit text-sm underline">{t('referral.mine')}</Link>
          </AlertDescription>
        </Alert>
      )}
    </section>
  )
}
