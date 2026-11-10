import { getAccountDeletion, useAuth } from '@jabiz/client'
import { Badge, Button } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { LogOut, Store, Trash2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { CERTIFICATIONS_QUERY } from '../certificates/CertificatesPart'
import { readMyCertifications } from '../certificates/api'
import { MY_SPONSOR_PATH, useMySponsor } from '../mysponsor/api'
import { BecomeSponsor } from '../mysponsor/BecomeSponsor'
import { MenuItem, MenuList } from '../pages/Menu'
import { TitleBadge } from '../pages/TitleBadge'
import { PROFILE_QUERY } from '../pages/ProfileForm'
import { ACCOUNT_DELETION_PATH, ACCOUNT_PATH } from '../pages/accountPaths'
import { APP } from '../messages'
import { formatKudos } from '../qb/kudos'
import { readMyProfile } from '../qb/api'
import { QB } from '../qb/messages'
import { Link } from '../qb/router'
import { readWallet, WALLET_PATH, WALLET_SUMMARY_QUERY } from '../wallet/api'
import { AreaHeader } from './AreaHeader'
import { ME_OWNED, ME_SETTINGS, PRIZES_PATH, type MeRow } from './areas'
import { useOpenPrizes } from './prizes'
import { useSignOut } from './signOut'
import './messages'

/** What is shown where a number cannot be read: the link stays. */
const UNKNOWN = '–'

/** One of the three numbers at the top of "Me": a link to the page that has the whole. */
function NumberTile({ to, name, value, note, testId }: {
  to: string
  name: string
  value: string
  note?: string
  testId: string
}) {
  return (
    <li>
      <Link to={to} data-testid={testId} aria-label={note ? `${name} ${value}, ${note}` : `${name} ${value}`}
        className="hover:bg-accent focus-visible:ring-ring/50 flex h-full flex-col items-center gap-0.5 rounded-lg border p-3 text-center outline-none focus-visible:ring-[3px]">
        <span className="text-muted-foreground text-xs">{name}</span>
        <span className="text-xl font-semibold tabular-nums" data-testid={`${testId}-value`}>{value}</span>
        {note && <Badge variant="secondary" data-testid={`${testId}-note`}>{note}</Badge>}
      </Link>
    </li>
  )
}

/** The three numbers: Kudos (with "Coming soon" while they cannot be used), prizes to collect, certificates. */
function Numbers() {
  const { t } = useTranslation(APP)
  const wallet = useQuery({ queryKey: WALLET_SUMMARY_QUERY, queryFn: readWallet })
  const prizes = useOpenPrizes()
  const certificates = useQuery({ queryKey: CERTIFICATIONS_QUERY, queryFn: readMyCertifications })
  return (
    <ul className="grid grid-cols-3 gap-2" aria-label={t('me.numbersLabel')} data-testid="me-numbers">
      <NumberTile testId="me-number-kudos" to={WALLET_PATH} name={t('me.numbers.kudos')}
        value={wallet.data ? formatKudos(wallet.data.available) : UNKNOWN}
        note={wallet.data?.usable === false ? t('me.soon') : undefined} />
      <NumberTile testId="me-number-prizes" to={PRIZES_PATH} name={t('me.numbers.prizes')}
        value={prizes === undefined ? UNKNOWN : String(prizes)} />
      <NumberTile testId="me-number-certificates" to={`${ACCOUNT_PATH}/certificates`}
        name={t('me.numbers.certificates')} value={certificates.data ? String(certificates.data.length) : UNKNOWN} />
    </ul>
  )
}

function Row({ row }: { row: MeRow }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const text = (key: string) => (row.ns === 'qb' ? tQb(key) : t(key))
  return (
    <MenuItem to={row.path} icon={row.icon} title={text(row.title)} hint={row.hint ? text(row.hint) : undefined}
      testId={row.key === 'about' ? 'account-about' : `account-menu-${row.key}`} />
  )
}

const DELETION_QUERY = ['qb', 'account-deletion']

/**
 * The way to deleting the account (Q71; Apple 5.1.1(v)): where the app entry allows it (`QB_APP_ACCOUNT_DELETION`), or
 * while a request is pending even if it no longer does.
 */
function DeletionEntry() {
  const { t } = useTranslation(APP)
  const status = useQuery({ queryKey: DELETION_QUERY, queryFn: getAccountDeletion, retry: false })
  if (!status.data || !(status.data.available || status.data.pending)) return null
  return (
    <MenuList label={t('account.deletion.group')} testId="account-deletion-menu">
      <MenuItem to={ACCOUNT_DELETION_PATH} icon={Trash2} title={t('account.deletion.title')}
        hint={t('account.deletion.menuHint')} testId="account-menu-delete" />
    </MenuList>
  )
}

/**
 * "Me" (F-F4 section 2.4): who is signed in, the three numbers (Kudos, prizes to collect, certificates), what the taker
 * has, and the settings; below, deleting the account where the entry allows it, and signing out. The rows are
 * `ME_OWNED` and `ME_SETTINGS` in `frame/areas.ts`.
 */
export function MePage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const { email } = useAuth()
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  const sponsor = useMySponsor().data
  const signOut = useSignOut()
  const name = profile.data?.displayName
  return (
    <section className="flex flex-col gap-6" aria-labelledby="account-title" data-testid="account">
      <AreaHeader area="me" id="account-title" title={t('frame.areas.me')}
        badge={<TitleBadge>{(name || email || '?').trim().charAt(0).toUpperCase()}</TitleBadge>}
        note={(
          <p className="text-muted-foreground truncate text-sm" data-testid="account-who">
            {name && email ? `${name} · ${email}` : name || email}
          </p>
        )} />
      <Numbers />
      <MenuList label={t('me.owned')} testId="me-owned">
        {ME_OWNED.map((row) => <Row key={row.key} row={row} />)}
        {/* Q68: a taker who is a Sponsor too has the way to their own page here as well as in the header. */}
        {sponsor && (
          <MenuItem to={MY_SPONSOR_PATH} icon={Store} title={t('mySponsor.nav')} testId="account-menu-my-sponsor" />
        )}
      </MenuList>
      {/* Q69: a taker who is no sponsor may apply, with the certification. */}
      <BecomeSponsor />
      <MenuList label={t('me.settings')} testId="me-settings">
        {ME_SETTINGS.map((row) => <Row key={row.key} row={row} />)}
      </MenuList>
      <DeletionEntry />
      <Button variant="outline" className="self-start" onClick={signOut} data-testid="sign-out">
        <LogOut aria-hidden />
        {tQb('signOut')}
      </Button>
    </section>
  )
}
