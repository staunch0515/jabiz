import { getAccountDeletion, useAuth } from '@jabiz/client'
import { Button, Input, Label } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { ChevronRight, LogOut, Search } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { MY_SPONSOR_PATH, useMySponsor } from '../mysponsor/api'
import { BecomeSponsor } from '../mysponsor/BecomeSponsor'
import { ACCOUNT_DELETION_PATH } from '../pages/accountPaths'
import { APP } from '../messages'
import { QB } from '../qb/messages'
import { Link } from '../qb/router'
import { AreaHeader } from './AreaHeader'
import { ME_SETTINGS, type MeRow } from './areas'
import { useSignOut } from './signOut'
import './messages'

/** A row of the settings: its words, then the arrow (Q113 §9.1 "设置": plain text rows of 52 px). */
interface SettingsLine {
  key: string
  path: string
  title: string
  hint?: string
  testId: string
}

const DELETION_QUERY = ['qb', 'account-deletion']

function Line({ line }: { line: SettingsLine }) {
  return (
    <li>
      <Link to={line.path} data-testid={line.testId}
        className="hover:bg-accent/60 flex min-h-[52px] items-center gap-3 px-4 py-2">
        <span className="flex min-w-0 flex-1 flex-col">
          <span>{line.title}</span>
          {line.hint && <span className="text-muted-foreground text-xs">{line.hint}</span>}
        </span>
        <ChevronRight aria-hidden className="text-muted-foreground size-[19px] shrink-0" />
      </Link>
    </li>
  )
}

/**
 * The settings (Q113d-2, the prototype's 设置; what "Me" was a menu of before, Q56 and F-F4): a search over the rows,
 * then each part of the account, the Sponsor account (or applying, Q69), help and the texts as rows of plain words,
 * deleting the account where the entry allows it (Q71), and signing out. The rows are `ME_SETTINGS` in
 * `frame/areas.ts`; the drawer's "Settings" leads here.
 */
export function SettingsPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const { email } = useAuth()
  const sponsor = useMySponsor().data
  const signOut = useSignOut()
  const deletion = useQuery({ queryKey: DELETION_QUERY, queryFn: getAccountDeletion, retry: false })
  const [text, setText] = useState('')
  const label = (row: MeRow, key: string) => (row.ns === 'qb' ? tQb(key) : t(key))
  const lines: SettingsLine[] = [
    ...ME_SETTINGS.map((row) => ({ key: row.key, path: row.path, title: label(row, row.title),
      hint: row.hint ? label(row, row.hint) : undefined,
      testId: row.key === 'about' ? 'account-about' : `account-menu-${row.key}` })),
    // Q68: a taker who is a Sponsor too has the way to their own page here as well as in the header.
    ...(sponsor ? [{ key: 'my-sponsor', path: MY_SPONSOR_PATH, title: t('mySponsor.nav'),
      testId: 'account-menu-my-sponsor' }] : []),
    // Q71 (Apple 5.1.1(v)): where the app entry allows it, or while a request is pending even if it no longer does.
    ...(deletion.data && (deletion.data.available || deletion.data.pending)
      ? [{ key: 'delete', path: ACCOUNT_DELETION_PATH, title: t('account.deletion.title'),
        hint: t('account.deletion.menuHint'), testId: 'account-menu-delete' }] : []),
  ]
  const words = text.trim().toLocaleLowerCase()
  const shown = words
    ? lines.filter((line) => `${line.title} ${line.hint ?? ''}`.toLocaleLowerCase().includes(words)) : lines
  return (
    <section className="flex flex-col gap-3" aria-labelledby="settings-title" data-testid="settings">
      <AreaHeader area="me" id="settings-title" title={t('me.settings')}
        note={email ? <span className="truncate" data-testid="account-who">{email}</span> : undefined} />
      <div className="relative">
        <Label htmlFor={`${id}-q`} className="sr-only">{t('settings.search')}</Label>
        <Search aria-hidden className="text-muted-foreground pointer-events-none absolute top-1/2 left-4 size-[18px] -translate-y-1/2" />
        <Input id={`${id}-q`} type="search" value={text} placeholder={t('settings.search')} maxLength={60}
          className="border-qb-outline h-[42px] rounded-full pl-10" data-testid="settings-search"
          onChange={(event) => setText(event.target.value)} />
      </div>
      <nav aria-label={t('me.settings')} data-testid="me-settings" className="-mx-4">
        {shown.length === 0
          ? <p className="text-muted-foreground px-4 py-3" role="status" data-testid="settings-none">{t('settings.noMatch')}</p>
          : <ul className="flex flex-col">{shown.map((line) => <Line key={line.key} line={line} />)}</ul>}
      </nav>
      {/* Q69: a taker who is no sponsor may apply, with the certification. */}
      {!words && <BecomeSponsor />}
      {!words && (
        <Button variant="outline" className="self-start rounded-full" onClick={signOut} data-testid="sign-out">
          <LogOut aria-hidden />
          {tQb('signOut')}
        </Button>
      )}
    </section>
  )
}
