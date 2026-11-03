import { formatDateTime } from '@jabiz/client'
import { Alert, AlertDescription, AlertTitle, Badge, Button, Card, CardContent, CardDescription, CardHeader, CardTitle,
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, Check, Circle, Copy, ExternalLink, QrCode } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { readMySponsorPage } from '../page/api'
import { SPONSOR_PAGE_QUERY } from '../page/SponsorPageEditor'
import { readMyProducts } from '../products/api'
import { PRODUCTS_QUERY } from '../products/ProductsPage'
import { readRewardSettings, readStaff, REWARD_SETTINGS_QUERY } from '../publishing/api'
import { useMailServer } from '../publishing/MailServerPage'
import { qrFileName, qrImageUrl, qrPng, qrSvg } from '../publishing/qr'
import { useRewardMethodLabel } from '../publishing/shared'
import { STAFF_QUERY } from '../publishing/StaffPage'
import { UNDERSTANDING_SPONSOR } from '../qb/ids'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { appPublicationLink, sponsorPageLink } from '../qb/page'
import { Link } from '../qb/router'
import { UnderstandingCards } from '../qb/UnderstandingCards'
import { useMySponsor } from './api'
import { checklist, progress, type ChecklistKey } from './checklist'
import { profilePath, type ProfileSection } from './paths'
import { PreviewCard } from './PreviewCard'

const SECTION_OF: Record<ChecklistKey, ProfileSection> = {
  basics: 'basics', page: 'page', products: 'products', staff: 'staff', mailServer: 'mail-server',
}

/**
 * The hub's first page (Q79, D-Q79-4): where the sponsor stands (the review), what is set up and what is left (the
 * checklist), the shop page as a card with its link and QR code, and the reward methods the platform allows. Reads
 * what the other pages read; nothing of its own.
 */
export function OverviewPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const sponsor = useMySponsor()
  const page = useQuery({ queryKey: SPONSOR_PAGE_QUERY, queryFn: readMySponsorPage })
  const products = useQuery({ queryKey: PRODUCTS_QUERY, queryFn: readMyProducts })
  const staff = useQuery({ queryKey: STAFF_QUERY, queryFn: readStaff })
  const mailServer = useMailServer()
  const rewards = useQuery({ queryKey: REWARD_SETTINGS_QUERY, queryFn: readRewardSettings, staleTime: 60_000 })
  if (sponsor.isPending) return <PageState kind="loading" />
  if (sponsor.isError) return <PageState kind="error" description={tQb('failed')} />
  const status = sponsor.data?.reviewStatus ?? 'NONE'
  const items = checklist({
    sponsor: sponsor.data ?? null,
    page: page.data ?? null,
    listedProducts: (products.data ?? []).filter((p) => p.status === 'LISTED').length,
    staff: (staff.data ?? []).length,
    mailServerVerified: !!mailServer.data?.verifiedAt,
  })
  const { done, total } = progress(items)
  const next = status !== 'APPROVED' ? 'basics' : page.data?.status === 'PUBLISHED' ? null : 'page'
  return (
    <div className="flex flex-col gap-6 lg:grid lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
      <div className="flex min-w-0 flex-col gap-6">
        <Card data-testid="profile-status" data-status={status}>
          <CardHeader>
            <CardTitle className="flex flex-wrap items-center gap-2">
              {t('profile.status.title')}
              <Badge variant={status === 'APPROVED' ? 'success' : status === 'REJECTED' ? 'destructive' : 'secondary'}>
                {t(`onboarding.status.${status}`)}
              </Badge>
            </CardTitle>
            <CardDescription>
              {status === 'APPROVED' && sponsor.data?.approvedAt
                ? t('onboarding.approvedAt', { time: formatDateTime(sponsor.data.approvedAt) })
                : status === 'PENDING' && sponsor.data?.submittedAt
                  ? t('onboarding.submittedAt', { time: formatDateTime(sponsor.data.submittedAt) })
                  : t(`profile.status.${status === 'APPROVED' ? 'approved' : status === 'PENDING' ? 'pending'
                    : status === 'REJECTED' ? 'rejected' : 'none'}`)}
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {status === 'REJECTED' && sponsor.data?.rejectReason && (
              <Alert variant="destructive" data-testid="profile-reject-reason">
                <AlertTitle>{t('onboarding.rejectReason')}</AlertTitle>
                <AlertDescription className="whitespace-pre-wrap">{sponsor.data.rejectReason}</AlertDescription>
              </Alert>
            )}
            {next && (
              <Button asChild className="self-start">
                <Link to={profilePath(next)} data-testid="profile-next">
                  {t(`profile.status.next.${next}`)}
                  <ArrowRight aria-hidden />
                </Link>
              </Button>
            )}
          </CardContent>
        </Card>

        {/* Q86 (D-Q86-2): the sponsor's terms quiz, answered in the app (one account, two front ends). */}
        <UnderstandingCards kinds={UNDERSTANDING_SPONSOR} hrefOf={appPublicationLink} held={false}
          testId="profile-understanding" />

        <Card data-testid="profile-checklist">
          <CardHeader>
            <CardTitle>{t('profile.checklist.title')}</CardTitle>
            <CardDescription>{t('profile.checklist.progress', { done, total })}</CardDescription>
          </CardHeader>
          <CardContent>
            <ol className="flex flex-col divide-y">
              {items.map((item) => (
                <li key={item.key} className="flex flex-wrap items-center gap-3 py-3"
                  data-testid={`profile-check-${item.key}`} data-done={item.done}>
                  {item.done
                    ? <Check aria-hidden className="text-success size-5 shrink-0" />
                    : <Circle aria-hidden className="text-muted-foreground size-5 shrink-0" />}
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="font-medium">
                      {t(`profile.checklist.${item.key}`)}
                      {item.optional && (
                        <span className="text-muted-foreground ms-2 text-xs font-normal">{t('profile.checklist.optional')}</span>
                      )}
                      <span className="sr-only">{item.done ? t('profile.checklist.done') : t('profile.checklist.todo')}</span>
                    </span>
                    <span className="text-muted-foreground text-sm">{t(`profile.checklist.${item.key}Hint`)}</span>
                  </span>
                  <Button asChild variant={item.done ? 'ghost' : 'outline'} size="sm">
                    <Link to={profilePath(SECTION_OF[item.key])}>
                      {item.done ? t('profile.checklist.open') : t('profile.checklist.go')}
                    </Link>
                  </Button>
                </li>
              ))}
            </ol>
          </CardContent>
        </Card>

        <Card data-testid="profile-methods">
          <CardHeader>
            <CardTitle>{t('profile.methods.title')}</CardTitle>
            <CardDescription>{t('profile.methods.hint')}</CardDescription>
          </CardHeader>
          <CardContent>
            {rewards.isPending && <PageState kind="loading" />}
            {rewards.isError && <p className="text-destructive text-sm">{tQb('failed')}</p>}
            {rewards.isSuccess && <RewardMethods methods={rewards.data.methods} max={rewards.data.maxRewardPerAttempt} />}
          </CardContent>
        </Card>
      </div>

      <aside className="flex flex-col gap-4" data-testid="profile-aside">
        {page.isPending ? <PageState kind="loading" /> : (
          <>
            <PreviewCard values={{
              company: sponsor.data?.company ?? null,
              avatar: sponsor.data?.avatar ?? null,
              headline: page.data?.headline ?? '',
              cover: page.data?.cover ?? null,
              handle: page.data?.handle ?? '',
              status: page.data?.status ?? 'NONE',
            }} />
            {page.data?.status === 'PUBLISHED' && <PageLinks handle={page.data.handle} company={sponsor.data?.company ?? ''} />}
          </>
        )}
        <Button asChild variant="link" className="self-start px-0">
          <Link to="/stats" data-testid="profile-stats-link">{t('profile.statsLink')}</Link>
        </Button>
      </aside>
    </div>
  )
}

/** The methods the sponsor may publish with, named; none yet is said so. */
function RewardMethods({ methods, max }: { methods: readonly string[]; max: number | null }) {
  const { t } = useTranslation(SPONSOR)
  const label = useRewardMethodLabel()
  if (methods.length === 0) return <p className="text-muted-foreground text-sm" data-testid="profile-methods-none">{t('profile.methods.none')}</p>
  return (
    <div className="flex flex-col gap-2">
      <ul className="flex flex-wrap gap-2">
        {methods.map((method) => <li key={method}><Badge variant="outline" data-testid="profile-method">{label(method)}</Badge></li>)}
      </ul>
      {max != null && <p className="text-muted-foreground text-sm">{t('profile.methods.max', { amount: formatKudos(max) })}</p>}
    </div>
  )
}

/** The published page's link: open, copy, and its QR code to download (D-Q79-4). */
function PageLinks({ handle, company }: { handle: string; company: string }) {
  const { t } = useTranslation(SPONSOR)
  const link = sponsorPageLink(handle)
  const [copied, setCopied] = useState(false)
  const [qr, setQr] = useState(false)
  return (
    <div className="flex flex-wrap gap-2" data-testid="profile-links">
      <Button asChild variant="outline" size="sm">
        <a href={link} target="_blank" rel="noreferrer" data-testid="profile-preview-open">
          <ExternalLink aria-hidden />{t('page.open')}
        </a>
      </Button>
      <Button type="button" variant="outline" size="sm" data-testid="profile-preview-copy" onClick={async () => {
        try {
          await navigator.clipboard.writeText(link)
          setCopied(true)
        } catch {
          setCopied(false)
        }
      }}>
        <Copy aria-hidden />{copied ? t('page.copied') : t('page.copy')}
      </Button>
      <Button type="button" variant="outline" size="sm" data-testid="profile-preview-qr" onClick={() => setQr(true)}>
        <QrCode aria-hidden />{t('profile.preview.qr')}
      </Button>
      {qr && <QrDialog link={link} name={company || handle} onClose={() => setQr(false)} />}
    </div>
  )
}

/** The page's QR code, drawn in the browser, with the files to download (as a publication's, Q21). */
function QrDialog({ link, name, onClose }: { link: string; name: string; onClose: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const [image, setImage] = useState<string | null>(null)
  useEffect(() => {
    let live = true
    void qrImageUrl(link).then((url) => {
      if (live) setImage(url)
    })
    return () => {
      live = false
    }
  }, [link])
  const download = async (make: () => Promise<Blob>, extension: 'png' | 'svg') => {
    const blob = await make()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = qrFileName(name, 'page', extension)
    a.click()
    URL.revokeObjectURL(url)
  }
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-sm" data-testid="profile-qr-dialog">
        <DialogHeader>
          <DialogTitle>{t('profile.preview.qrTitle')}</DialogTitle>
          <DialogDescription className="break-all">{link}</DialogDescription>
        </DialogHeader>
        {image && <img src={image} alt={t('profile.preview.qrAlt')} className="mx-auto size-48" data-testid="profile-qr-image" />}
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="outline" size="sm" data-testid="profile-qr-png"
            onClick={() => void download(() => qrPng(link), 'png')}>{t('profile.preview.downloadPng')}</Button>
          <Button type="button" variant="outline" size="sm" data-testid="profile-qr-svg"
            onClick={() => void download(() => qrSvg(link), 'svg')}>{t('profile.preview.downloadSvg')}</Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
