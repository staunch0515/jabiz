import { i18n, useAuth } from '@jabiz/client'
import { Badge, Button, PageState, PageTabs } from '@jabiz/ui'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { CalendarDays, MapPin } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { CERTIFICATIONS_QUERY, CertificatesPart } from '../certificates/CertificatesPart'
import { MapsList } from '../maps/MapsPage'
import { APP } from '../messages'
import { HistoryItem } from '../pages/HistoryPage'
import { PROFILE_QUERY } from '../pages/ProfileForm'
import { ACCOUNT_PATH, accountPath } from '../pages/accountPaths'
import { useStartAttempt } from '../play/useStartAttempt'
import { readMyProfile } from '../qb/api'
import { readCertificates, TAKER_CERTIFICATES } from '../qb/certificates'
import { kudosName } from '../qb/kudos'
import { QB } from '../qb/messages'
import { readHistory } from '../qb/play'
import { problemText } from '../qb/problems'
import { Link, useLocation } from '../qb/router'
import { REFERRALS_PATH } from '../referral/api'
import { ReferralsContent } from '../referral/ReferralsPage'
import { KudosOverview } from '../wallet/KudosOverview'
import { readWallet, WALLET_PATH, WALLET_SUMMARY_QUERY } from '../wallet/api'
import { MAPS_PATH } from '../maps/paths'
import { AreaHeader } from './AreaHeader'
import { FrameLink } from './FrameLink'
import { Avatar } from '../avatar/Avatar'
import { PRIZES_PATH } from './areas'
import { useOpenPrizes } from './prizes'
import './messages'

/** What is shown where a number cannot be read: the link stays. */
const UNKNOWN = '–'

/** The tabs of My page (Q113d-2, the prototype's 我的主页), each at `/account?tab=<key>` (the first at `/account`). */
export const ME_TABS = ['answered', 'kudos', 'certificates', 'maps', 'referrals'] as const
export type MeTab = (typeof ME_TABS)[number]

/** The rows of "Answered" on My page; the whole list is the area's page. */
const RECENT = 5

/** The tab an address shows (`?tab=`), the first when none or another is named. */
export function meTabOf(search: string): MeTab {
  const tab = new URLSearchParams(search).get('tab')
  return (ME_TABS as readonly string[]).includes(tab ?? '') ? (tab as MeTab) : 'answered'
}

function tabPath(tab: MeTab): string {
  return tab === 'answered' ? ACCOUNT_PATH : `${ACCOUNT_PATH}?tab=${tab}`
}

/** One of the three numbers under the name: "100 Kudos", a link to the page that has the whole. */
function Count({ to, name, value, note, testId }: {
  to: string; name: string; value: string; note?: string; testId: string }) {
  return (
    <Link to={to} data-testid={testId} aria-label={note ? `${name} ${value}, ${note}` : `${name} ${value}`}
      className="text-muted-foreground inline-flex items-center gap-1 text-sm underline-offset-2 hover:underline">
      <b className="text-foreground tabular-nums" data-testid={`${testId}-value`}>{value}</b>
      {name}
      {note && <Badge variant="secondary" className="ml-1" data-testid={`${testId}-note`}>{note}</Badge>}
    </Link>
  )
}

/** The month and year the taker joined, in the interface's language ("2026年11月"). */
function joinedText(createdAt: string | null | undefined): string | null {
  if (!createdAt) return null
  const at = new Date(createdAt)
  if (Number.isNaN(at.getTime())) return null
  return new Intl.DateTimeFormat(i18n.language, { year: 'numeric', month: 'long' }).format(at)
}

/** The city and the country of the profile, the country by its name in the interface's language. */
function placeText(city: string | null | undefined, country: string | null | undefined): string | null {
  let name: string | null
  try {
    name = country ? new Intl.DisplayNames([i18n.language], { type: 'region' }).of(country) ?? country : null
  } catch {
    name = country ?? null
  }
  return [city, name].filter(Boolean).join(', ') || null
}

/** "Answered" on My page: the latest attempts as the area shows them, then the way to all of them. */
function RecentAnswers() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const [at] = useState(() => new Date().toISOString())
  const start = useStartAttempt()
  const recent = useInfiniteQuery({
    queryKey: ['qb', 'history', at, '', 'all', i18n.language, 'me'],
    queryFn: () => readHistory({ at, offset: 0, limit: RECENT }),
    initialPageParam: 0,
    getNextPageParam: () => undefined,
  })
  if (recent.isPending) return <PageState kind="loading" />
  if (recent.isError) {
    return <PageState kind="error" description={problemText(recent.error)}
      action={<Button variant="outline" onClick={() => void recent.refetch()}>{tQb('retry')}</Button>} />
  }
  const rows = recent.data.pages[0]?.items ?? []
  if (rows.length === 0) {
    return (
      <Empty title={t('me.noAnswers')} hint={t('me.noAnswersHint')} testId="me-answered-empty"
        action={<Button asChild size="lg" className="h-[52px] rounded-full px-8 text-[17px] font-bold"><Link to="/">{t('me.toQuizzes')}</Link></Button>} />
    )
  }
  return (
    <div className="flex flex-col">
      <ul className="flex flex-col" data-testid="me-answered">
        {rows.map((row) => (
          <HistoryItem key={row.attemptId} row={row} at={at} starting={start.isPending}
            onContinue={() => start.mutate(row.publicationId)} />
        ))}
      </ul>
      <Link to="/history" className="text-primary px-4 py-3 font-medium underline-offset-2 hover:underline"
        data-testid="me-answered-all">{t('me.seeAll')}</Link>
    </div>
  )
}

/** A tab with nothing in it yet (the prototype's): a large title, what will come, and the way there. */
function Empty({ title, hint, action, testId }: { title: string; hint: string; action?: ReactNode; testId: string }) {
  return (
    <section className="flex flex-col items-start px-8 pt-8 pb-10" data-testid={testId}>
      <h2 className="text-[31px] leading-9 font-extrabold">{title}</h2>
      <p className="text-muted-foreground mt-2">{hint}</p>
      {action && <div className="mt-7">{action}</div>}
    </section>
  )
}

/** A tab's own page beside its contents (the whole of it, with what the tab leaves out). */
function More({ to, label, testId }: { to: string; label: string; testId: string }) {
  return (
    <Link to={to} className="text-primary self-start font-medium underline-offset-2 hover:underline" data-testid={testId}>
      {label}
    </Link>
  )
}

function TabContent({ tab }: { tab: MeTab }) {
  const { t } = useTranslation(APP)
  switch (tab) {
    case 'answered': return <div className="-mx-4"><RecentAnswers /></div>
    case 'kudos':
      return (
        <div className="flex flex-col gap-3 pt-3">
          <KudosOverview />
          <More to={WALLET_PATH} label={t('me.rows.kudos')} testId="me-kudos-all" />
        </div>
      )
    case 'certificates': return <div className="pt-3"><CertificatesPart /></div>
    case 'maps':
      return (
        <div className="flex flex-col gap-3 pt-3">
          <p className="text-muted-foreground text-sm">{t('maps.intro')}</p>
          <MapsList />
          <More to={MAPS_PATH} label={t('maps.title')} testId="me-maps-all" />
        </div>
      )
    case 'referrals':
      return (
        <div className="flex flex-col gap-3 pt-3">
          <ReferralsContent />
          <More to={REFERRALS_PATH} label={t('referral.page.title')} testId="me-referrals-all" />
        </div>
      )
  }
}

/**
 * My page (Q113d-2, the prototype's 我的主页, as an X profile): the head with the name, a banner of 3:1 with the
 * picture of 93 px over its edge, "Edit profile", the name, "Taker", where they live and since when, the three numbers
 * (Kudos with "Coming soon" while they cannot be used, prizes to collect, certificates), then tabs across: answered,
 * Kudos, certificates, knowledge maps and referrals, each showing its list here and leading to its own page. The
 * settings that "Me" listed are the settings' page (`SettingsPage`, the drawer's "Settings").
 */
export function MePage() {
  const { t } = useTranslation(APP)
  const { displayName } = useAuth()
  const { search } = useLocation()
  const tab = meTabOf(search)
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  const wallet = useQuery({ queryKey: WALLET_SUMMARY_QUERY, queryFn: readWallet })
  const prizes = useOpenPrizes()
  const certificates = useQuery({ queryKey: CERTIFICATIONS_QUERY, queryFn: () => readCertificates(TAKER_CERTIFICATES) })
  const history = useQuery({ queryKey: ['qb', 'history-count'], queryFn: () => readHistory({ at: new Date().toISOString(),
    offset: 0, limit: 1, count: true }) })
  const name = profile.data?.displayName || displayName || ''
  const place = placeText(profile.data?.city, profile.data?.countryCode)
  const joined = joinedText(profile.data?.createdAt)
  const answered = history.data?.total
  return (
    <section className="flex flex-col" aria-labelledby="account-title" data-testid="account">
      <AreaHeader area="me" id="account-title" title={name || t('frame.areas.me')}
        note={answered !== undefined ? <span data-testid="me-answered-count">{t('me.answered', { count: answered })}</span> : undefined} />
      <div aria-hidden className="bg-accent -mx-4 aspect-[3/1] max-h-[200px]" data-testid="me-banner" />
      <div className="relative flex flex-col">
        <Avatar data-testid="me-avatar"
          className="border-background absolute -top-[43px] left-0 size-[93px] border-4 text-[34px]" />
        <div className="flex h-16 items-start justify-end pt-3">
          <Button asChild variant="outline" className="border-qb-outline h-9 rounded-full px-4 font-bold">
            <Link to={accountPath('profile')} data-testid="me-edit-profile">{t('me.edit')}</Link>
          </Button>
        </div>
        <p className="flex flex-col">
          <span className="text-xl font-extrabold" data-testid="me-name">{name}</span>
          <span className="text-muted-foreground">{t('me.taker')}</span>
        </p>
        {(place || joined) && (
          <p className="text-muted-foreground mt-3 flex flex-wrap gap-x-3 gap-y-1">
            {place && <span className="flex items-center gap-1" data-testid="me-place"><MapPin aria-hidden className="size-[18px]" />{place}</span>}
            {joined && <span className="flex items-center gap-1" data-testid="me-joined"><CalendarDays aria-hidden className="size-[18px]" />{t('me.joined', { date: joined })}</span>}
          </p>
        )}
        <nav aria-label={t('me.numbersLabel')} className="my-3 flex flex-wrap gap-x-5 gap-y-1" data-testid="me-numbers">
          <Count testId="me-number-kudos" to={WALLET_PATH} name={kudosName()}
            value={wallet.data ? new Intl.NumberFormat(i18n.language).format(Number(wallet.data.available) || 0) : UNKNOWN}
            note={wallet.data?.usable === false ? t('me.soon') : undefined} />
          <Count testId="me-number-prizes" to={PRIZES_PATH} name={t('me.numbers.prizes')}
            value={prizes === undefined ? UNKNOWN : String(prizes)} />
          <Count testId="me-number-certificates" to={accountPath('certificates')} name={t('me.numbers.certificates')}
            value={certificates.data ? String(certificates.data.filter((c) => !c.revokedAt).length) : UNKNOWN} />
        </nav>
      </div>
      <PageTabs label={t('me.tabsLabel')} current={tab} indicator="label" className="border-border -mx-4 border-b"
        tabs={ME_TABS.map((key) => ({ key, path: tabPath(key), label: t(`me.tabs.${key}`) }))}
        renderLink={(item, props) => <FrameLink to={item.path} {...props} />} />
      <div data-testid={`me-tab-${tab}`}><TabContent tab={tab} /></div>
    </section>
  )
}
