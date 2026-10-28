import {
  installOfferAllowed, readInstallDismissals, rememberInstallDismissed, useInstallPrompt, type InstallBrowser,
  type InstallPrompt,
} from '@jabiz/client'
import { Download, Ellipsis, ExternalLink, Menu, Settings, Share, Smartphone, SquarePlus, X } from 'lucide-react'
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
 * Offers to add the PWA to the home screen (phases 16zj and 17m): the browser's own confirmation where it has one
 * (Android), a guide to the Share menu on iOS, a guide to the browser's menu on the other Android browsers (steps for
 * Huawei's, Xiaomi's, QQ's, UC's and Firefox, general ones for the rest), and "open this in your browser" inside other
 * apps' browsers, where installing is impossible (the steps from WeChat's and QQ's "···"), marked as a warning. Nothing on a computer, nothing once installed. The application
 * chooses the moment by when it renders this (after something worthwhile, not on the first visit).
 */
export function PwaInstallBanner({
  appName, dismissible = true, rememberAs, snoozeDays = 30, maxDismissals = 3, prompt, now = Date.now, className,
}: PwaInstallBannerProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const detected = useInstallPrompt()
  const { situation, browser, install } = prompt ?? detected
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
      action = (
        <Button size="sm" data-testid="pwa-install-how" onClick={() => setGuide(true)}>{t('install.how')}</Button>
      )
      break
    case 'in-app':
      icon = <ExternalLink aria-hidden className="text-warning size-5 shrink-0" />
      // WeChat and QQ put "open in the browser" behind the "···" at the top right.
      text = t(browser === 'wechat' || browser === 'qq-app' ? 'install.inAppWechat' : 'install.inApp', { app: appName })
      break
    case 'installed':
      text = t('install.installed', { app: appName })
      break
  }
  const offered = situation !== 'installed' && situation !== 'in-app'
  const inApp = situation === 'in-app'

  return (
    <section aria-label={t('install.title', { app: appName })} data-testid="pwa-install" data-situation={situation}
      data-browser={browser}
      className={cn('bg-card text-card-foreground flex items-start gap-3 border p-3 text-sm', inApp && 'border-warning',
        className)}>
      {icon}
      <div className="min-w-0 flex-1 space-y-2">
        {offered && <p className="font-medium">{t('install.title', { app: appName })}</p>}
        {/* Inside another app nothing else works: the way out is the message, so it reads as one. */}
        <p className={inApp ? 'font-medium' : 'text-muted-foreground'}>{text}</p>
        {action}
      </div>
      {dismissible && (
        <Button variant="ghost" size="icon" className="-m-1 size-8 shrink-0" aria-label={t('install.dismiss')}
          data-testid="pwa-install-dismiss" onClick={close}>
          <X aria-hidden />
        </Button>
      )}
      {(situation === 'ios' || situation === 'menu') && (
        <PwaInstallGuide appName={appName} open={guide} onOpenChange={setGuide} situation={situation} browser={browser} />
      )}
    </section>
  )
}

export interface PwaInstallGuideProps {
  appName: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The Share menu of iOS (the default), or the menu of an Android browser (phase 17m). */
  situation?: 'ios' | 'menu'
  /** The Android browser whose menu to describe; general steps for `other`. */
  browser?: InstallBrowser
}

/** The menu button of each Android browser, and its item that adds to the home screen (message keys). */
const MENU_STEPS: Record<InstallBrowser, { open: string; add: string }> = {
  huawei: { open: 'menuBottom', add: 'addHuawei' },
  xiaomi: { open: 'menuBottom', add: 'addToolbox' },
  qq: { open: 'menuBottom', add: 'addToolbox' },
  uc: { open: 'menuBottom', add: 'addToolbox' },
  firefox: { open: 'menuFirefox', add: 'addFirefox' },
  wechat: { open: 'menuOther', add: 'addOther' },
  'qq-app': { open: 'menuOther', add: 'addOther' },
  other: { open: 'menuOther', add: 'addOther' },
}

/**
 * The steps of adding the PWA to the home screen where no page can start it. On iOS: the Share button (behind "…" in
 * newer Safari), "Add to Home Screen", "Add"; and that the home-screen app signs in on its own (its storage is not
 * Safari's). On an Android browser without the browser's own confirmation: its menu, its item, the confirmation; and,
 * except on Firefox, that many phones (Huawei, Xiaomi, vivo, OPPO) let a browser put an icon on the home screen only once
 * its "home screen shortcuts" permission is on.
 */
export function PwaInstallGuide({ appName, open, onOpenChange, situation = 'ios', browser = 'other' }:
  PwaInstallGuideProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const menu = situation === 'menu'
  const steps = menu
    ? [
        { icon: <Menu aria-hidden />, text: t(`install.${MENU_STEPS[browser].open}`) },
        { icon: <SquarePlus aria-hidden />, text: t(`install.${MENU_STEPS[browser].add}`) },
        { icon: <Smartphone aria-hidden />, text: t('install.menuConfirm', { app: appName }) },
      ]
    : [
        { icon: <Share aria-hidden />, text: t('install.step1') },
        { icon: <SquarePlus aria-hidden />, text: t('install.step2') },
        { icon: <Smartphone aria-hidden />, text: t('install.step3', { app: appName }) },
      ]
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent data-testid="pwa-install-guide" data-situation={situation} data-browser={menu ? browser : undefined}>
        <DialogHeader>
          <DialogTitle>{t('install.title', { app: appName })}</DialogTitle>
          <DialogDescription>{t(menu ? 'install.menuIntro' : 'install.guideIntro')}</DialogDescription>
        </DialogHeader>
        <ol className="space-y-3 text-sm">
          {steps.map((step, index) => (
            <li key={index} className="flex items-start gap-3">
              <span className="bg-muted flex size-8 shrink-0 items-center justify-center [&_svg]:size-4">{step.icon}</span>
              <span className="pt-1.5">{index + 1}. {step.text}</span>
            </li>
          ))}
        </ol>
        {menu ? (
          browser !== 'firefox' && (
            <p className="text-muted-foreground flex items-start gap-2 text-sm" data-testid="pwa-install-permission">
              <Settings aria-hidden className="mt-0.5 size-4 shrink-0" />{t('install.shortcutPermission')}
            </p>
          )
        ) : (
          <>
            <p className="text-muted-foreground flex items-start gap-2 text-sm">
              <Ellipsis aria-hidden className="mt-0.5 size-4 shrink-0" />{t('install.moreMenu')}
            </p>
            <p className="text-sm font-medium" data-testid="pwa-install-sign-in">{t('install.signInAgain', { app: appName })}</p>
          </>
        )}
        <DialogFooter>
          <DialogClose asChild>
            <Button>{t('install.done')}</Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
