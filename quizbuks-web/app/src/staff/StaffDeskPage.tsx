import { formatDateTime } from '@jabiz/client'
import { Alert, AlertDescription, AlertTitle, Button, notify, PageState } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Gift, X } from 'lucide-react'
import QRCode from 'qrcode'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import {
  CODE_QUERY,
  confirmClaim,
  COUNTER_QUERY,
  declineClaim,
  issueCode,
  notStaff,
  readCounter,
  readStaffOf,
  redeemLink,
  STAFF_QUERY,
} from './api'
import './messages'

/** The page asks for a new rotating code every minute, as the sponsor's console does; the server's lasts two. */
const ROTATE_EVERY = 60_000
/** How often the claims and the day's prizes are read while the page is open. */
const POLL_EVERY = 5_000

/** The start of the device's day, for the list of what was collected today. */
function startOfToday(): string {
  const day = new Date()
  day.setHours(0, 0, 0, 0)
  return day.toISOString()
}

/** The code as an SVG image URL for `<img>` (the CSP allows `data:` images); drawn here, nothing asked of a server. */
async function qrImageUrl(link: string): Promise<string> {
  const svg = await QRCode.toString(link, { type: 'svg', margin: 2, errorCorrectionLevel: 'M' })
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

/** The sponsor's rotating code, in the staff member's name: renewed every minute. */
function RotatingCode() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const code = useQuery({ queryKey: CODE_QUERY, refetchInterval: ROTATE_EVERY, refetchIntervalInBackground: false,
    staleTime: ROTATE_EVERY,
    queryFn: async () => {
      try {
        return await issueCode()
      } catch (error) {
        // Removed from the staff meanwhile: the page reads the place again and says so.
        if (notStaff(error)) void queryClient.invalidateQueries({ queryKey: STAFF_QUERY })
        throw error
      }
    } })
  const link = code.data ? redeemLink(code.data.redeemKey) : null
  const image = useQuery({ queryKey: ['staff-qr', link], queryFn: () => qrImageUrl(link!), enabled: !!link,
    staleTime: Infinity })
  if (code.isError) {
    return <PageState kind="error" description={problemText(code.error)}
      action={<Button variant="outline" onClick={() => void code.refetch()}>{tQb('retry')}</Button>} />
  }
  return (
    <section className="flex flex-col items-center gap-3 rounded-lg border p-4" aria-labelledby="staff-code-title"
      data-testid="staff-code">
      <h2 id="staff-code-title" className="text-lg font-semibold">{t('staff.codeTitle')}</h2>
      {image.data && link ? (
        // The image carries its own light background: it stays scannable in the dark appearance.
        <img src={image.data} alt={t('staff.codeAlt')} width={260} height={260} className="rounded-md"
          data-testid="staff-code-image" />
      ) : image.isError ? (
        <p role="alert" className="text-destructive text-sm">{t('staff.qrFailed')}</p>
      ) : (
        <div className="bg-muted size-[260px] animate-pulse rounded-md" aria-hidden />
      )}
      {link && <p className="text-muted-foreground text-center text-xs break-all" data-testid="staff-code-link">{link}</p>}
      <p className="text-muted-foreground text-xs">
        {code.data?.expiresAt ? t('staff.codeUntil', { time: formatDateTime(code.data.expiresAt) }) : ''}
      </p>
    </section>
  )
}

/** The claims from the sponsor's printed code: the person at the counter, or not. */
function Claims() {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  const claims = useQuery({ queryKey: [...COUNTER_QUERY, 'claims'], queryFn: () => readCounter({ status: 'CLAIMED' }),
    refetchInterval: POLL_EVERY, refetchIntervalInBackground: false })
  const answer = useMutation({
    mutationFn: ({ redemptionId, confirm }: { redemptionId: string; confirm: boolean }) =>
      confirm ? confirmClaim(redemptionId) : declineClaim(redemptionId),
    onSuccess: async (_output, { confirm }) => {
      notify.success(t(confirm ? 'staff.confirmed' : 'staff.declined'))
      await queryClient.invalidateQueries({ queryKey: COUNTER_QUERY })
    },
    onError: async (error) => {
      notify.error(problemText(error))
      await queryClient.invalidateQueries({ queryKey: COUNTER_QUERY })
      if (notStaff(error)) await queryClient.invalidateQueries({ queryKey: STAFF_QUERY })
    },
  })
  // A claim past its time to confirm is due again (the server refuses confirming it): the time is the reading's, so
  // the list is pure in the render and moves with each poll.
  const now = claims.dataUpdatedAt
  const items = (claims.data ?? []).filter((row) => !row.confirmBy || new Date(row.confirmBy).getTime() > now)
  return (
    <section className="flex flex-col gap-2" aria-labelledby="staff-claims-title" data-testid="staff-claims">
      <h2 id="staff-claims-title" className="text-lg font-semibold">{t('staff.claimsTitle')}</h2>
      {items.length === 0 ? (
        <p className="text-muted-foreground text-sm">{t('staff.claimsNone')}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {items.map((row) => (
            <li key={row.redemptionId} className="flex flex-col gap-2 rounded-lg border p-3" data-testid="staff-claim"
              data-id={row.redemptionId}>
              <span className="flex flex-wrap items-center gap-2">
                <Gift aria-hidden className="text-primary size-4" />
                <span className="font-mono font-semibold">{row.code}</span>
                <span>{row.takerName ?? '?'}</span>
              </span>
              <span className="whitespace-pre-line text-sm">{row.description}</span>
              {row.confirmBy && (
                <span className="text-muted-foreground text-xs">
                  {t('staff.claimBy', { time: formatDateTime(row.confirmBy) })}
                </span>
              )}
              <span className="flex gap-2">
                <Button size="sm" disabled={answer.isPending} data-testid="staff-claim-confirm"
                  onClick={() => answer.mutate({ redemptionId: row.redemptionId, confirm: true })}>
                  <Check aria-hidden />
                  {t('staff.confirm')}
                </Button>
                <Button size="sm" variant="outline" disabled={answer.isPending} data-testid="staff-claim-decline"
                  onClick={() => answer.mutate({ redemptionId: row.redemptionId, confirm: false })}>
                  <X aria-hidden />
                  {t('staff.decline')}
                </Button>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** The prizes the staff member handed over today, as they happen (the day is the device's, read at each poll). */
function Today() {
  const { t } = useTranslation(APP)
  const today = useQuery({ queryKey: [...COUNTER_QUERY, 'today'],
    queryFn: () => readCounter({ status: 'REDEEMED', since: startOfToday() }),
    refetchInterval: POLL_EVERY, refetchIntervalInBackground: false })
  const items = today.data ?? []
  return (
    <section className="flex flex-col gap-2" aria-labelledby="staff-today-title" data-testid="staff-today">
      <h2 id="staff-today-title" className="text-lg font-semibold">{t('staff.todayTitle')}</h2>
      {items.length === 0 ? (
        <p className="text-muted-foreground text-sm">{t('staff.todayNone')}</p>
      ) : (
        <ul className="flex flex-col gap-1 text-sm">
          {items.map((row) => (
            <li key={row.redemptionId} className="flex flex-wrap gap-2" data-testid="staff-today-item">
              <span className="font-mono">{row.code}</span>
              <span>{row.takerName ?? '?'}</span>
              <span className="text-muted-foreground">{row.redeemedAt ? formatDateTime(row.redeemedAt) : ''}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/**
 * The counter (docs/quizbuks/plans/Q39-sponsor-staff.md): a staff member hands out the sponsor's prizes with their
 * own phone. The sponsor's rotating code in their name, the claims from the sponsor's printed code, and what they
 * handed over today. Someone on no staff (removed meanwhile) is told so.
 */
export function StaffDeskPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const place = useQuery({ queryKey: STAFF_QUERY, queryFn: readStaffOf })
  if (place.isPending) return <PageState kind="loading" />
  if (place.isError && !notStaff(place.error)) {
    return <PageState kind="error" description={problemText(place.error)}
      action={<Button variant="outline" onClick={() => void place.refetch()}>{tQb('retry')}</Button>} />
  }
  if (!place.data) {
    return <PageState kind="empty" titleAs="h1" title={t('staff.notStaffTitle')} description={t('staff.notStaff')}
      action={<Link to="/account/id" className="underline underline-offset-4">{t('staff.toAccount')}</Link>} />
  }
  return (
    <section className="flex flex-col gap-4" aria-labelledby="staff-title" data-testid="staff-desk">
      <h1 id="staff-title" className="text-xl font-semibold">
        {t('staff.deskTitle', { sponsor: place.data.sponsorCompany ?? t('staff.theSponsor') })}
      </h1>
      <Alert>
        <Gift aria-hidden />
        <AlertTitle className="sr-only">{t('staff.codeTitle')}</AlertTitle>
        <AlertDescription>{t('staff.deskIntro')}</AlertDescription>
      </Alert>
      <RotatingCode />
      <Claims />
      <Today />
    </section>
  )
}
