import {
  installOfferAllowed, readInstallDismissals, rememberInstallDismissed, useInstallPrompt, type InstallPrompt,
} from '@jabiz/client'
import { Download, Ellipsis, ExternalLink, Share, Smartphone, SquarePlus, X } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '../components/ui/button'
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog'
import { cn } from '../lib/utils'
import { AUTH_NAMESPACE } from './messages'

export interface PwaInstallBannerProps {
  /** The application's name as on the home screen (the manifest's `short_name`). */
  appName: string
  /**
   * The offer can be closed (default): it then stays away `snoozeDays` and for good after `maxDismissals` closes,
   * remembered in this browser under `rememberAs`. A page that is the place to install (the account page) passes false:
   * no close button, nothing remembered.
   */
  dismissible?: boolean
  /** Where the closes are remembered: the SPA's prefix by default. */
  rememberAs?: string
  snoozeDays?: number
  maxDismissals?: number
  /** `useInstallPrompt()` by default (tests give their own). */
  prompt?: InstallPrompt
  /** The clock of the closes; `Date.now` by default. */
  now?: () => number
  className?: string
}

const DEFAULT_KEY = () => (import.meta.env?.BASE_URL ?? '/').replace(/^\/+|\/+$/g, '') || 'root'

/**
 * Offers to add the PWA to the home screen (phase 16zj): the browser's own confirmation where it has one (Android),
 * a guide to the Share menu on iOS, the menu on Firefox for Android, and "open this in your browser" inside other
 * apps' browsers, where installing is impossible. Nothing on a computer, nothing once installed. The application
 * chooses the moment by when it renders this (after something worthwhile, not on the first visit).
 */
export function PwaInstallBanner({
  appName, dismissible = true, rememberAs, snoozeDays = 30, maxDismissals = 3, prompt, now = Date.now, className,
}: PwaInstallBannerProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const browser = useInstallPrompt()
  const { situation, install } = prompt ?? browser
  const key = rememberAs ?? DEFAULT_KEY()
  const [closed, setClosed] = useState(() => dismissible
    && !installOfferAllowed(readInstallDismissals(key), now(), { days: snoozeDays, max: maxDismissals }))
  const [guide, setGuide] = useState(false)

  if (situation === 'none' || (situation === 'installed' && dismissible) || closed) return null

  const close = () => {
    rememberInstallDismissed(key, now())
    setClosed(true)
  }
  let text: ReactNode
  let action: ReactNode = null
  let icon = <Smartphone aria-hidden className="size-5 shrink-0" />
  switch (situation) {
    case 'prompt':
      text = t('install.prompt')
      action = (
        <Button size="sm" data-testid="pwa-install-button" onClick={() => void install()}>
          <Download aria-hidden />{t('install.install')}
        </Button>
      )
      break
    case 'ios':
      text = t('install.ios')
      action = (
        <Button size="sm" data-testid="pwa-install-how" onClick={() => setGuide(true)}>{t('install.how')}</Button>
      )
      break
    case 'menu':
      text = t('install.menu')
      break
    case 'in-app':
      icon = <ExternalLink aria-hidden className="size-5 shrink-0" />
      text = t('install.inApp', { app: appName })
      break
    case 'installed':
      text = t('install.installed', { app: appName })
      break
  }
  const offered = situation !== 'installed' && situation !== 'in-app'

  return (
    <section aria-label={t('install.title', { app: appName })} data-testid="pwa-install" data-situation={situation}
      className={cn('bg-card text-card-foreground flex items-start gap-3 border p-3 text-sm', className)}>
      {icon}
      <div className="min-w-0 flex-1 space-y-2">
        {offered && <p className="font-medium">{t('install.title', { app: appName })}</p>}
        <p className="text-muted-foreground">{text}</p>
        {action}
      </div>
      {dismissible && (
        <Button variant="ghost" size="icon" className="-m-1 size-8 shrink-0" aria-label={t('install.dismiss')}
          data-testid="pwa-install-dismiss" onClick={close}>
          <X aria-hidden />
        </Button>
      )}
      <PwaInstallGuide appName={appName} open={guide} onOpenChange={setGuide} />
    </section>
  )
}

export interface PwaInstallGuideProps {
  appName: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * The steps of adding the PWA to the home screen on iOS, where no page can start it: the Share button (behind "…" in
 * newer Safari), "Add to Home Screen", "Add"; and that the home-screen app signs in on its own (its storage is not
 * Safari's).
 */
export function PwaInstallGuide({ appName, open, onOpenChange }: PwaInstallGuideProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const steps = [
    { icon: <Share aria-hidden />, text: t('install.step1') },
    { icon: <SquarePlus aria-hidden />, text: t('install.step2') },
    { icon: <Smartphone aria-hidden />, text: t('install.step3', { app: appName }) },
  ]
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent data-testid="pwa-install-guide">
        <DialogHeader>
          <DialogTitle>{t('install.title', { app: appName })}</DialogTitle>
          <DialogDescription>{t('install.guideIntro')}</DialogDescription>
        </DialogHeader>
        <ol className="space-y-3 text-sm">
          {steps.map((step, index) => (
            <li key={index} className="flex items-start gap-3">
              <span className="bg-muted flex size-8 shrink-0 items-center justify-center [&_svg]:size-4">{step.icon}</span>
              <span className="pt-1.5">{index + 1}. {step.text}</span>
            </li>
          ))}
        </ol>
        <p className="text-muted-foreground flex items-start gap-2 text-sm">
          <Ellipsis aria-hidden className="mt-0.5 size-4 shrink-0" />{t('install.moreMenu')}
        </p>
        <p className="text-sm font-medium" data-testid="pwa-install-sign-in">{t('install.signInAgain', { app: appName })}</p>
        <DialogFooter>
          <DialogClose asChild>
            <Button>{t('install.done')}</Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
