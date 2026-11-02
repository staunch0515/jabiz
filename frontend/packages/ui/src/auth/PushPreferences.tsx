import {
  disablePush,
  enablePush,
  pushEnabledHere,
  pushSettings,
  pushSupported,
  setPushPreference,
  type PushOutcome,
  type PushSettings,
} from '@jabiz/client'
import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Spinner } from '../components/spinner'
import { Label } from '../components/ui/label'
import { Switch } from '../components/ui/switch'
import { cn } from '../lib/utils'
import { AuthError, Outcome } from './frame'
import { AUTH_NAMESPACE } from './messages'
import { failure } from './problems'

type Template = NonNullable<PushSettings['templates']>[number]

export interface PushPreferencesProps {
  /** Reads the settings; `pushSettings` of `@jabiz/client` by default. Pass a stable function. */
  load?: () => Promise<PushSettings>
  /** Whether this browser can receive push; `pushSupported` by default. */
  supported?: () => boolean
  /** Whether push is on here for the session, given the settings; `pushEnabledHere` by default. Pass a stable function. */
  enabledHere?: (settings: PushSettings) => Promise<boolean>
  /** Turns push on for this device; `enablePush` by default. */
  enable?: () => Promise<PushOutcome>
  /** Turns push off for this device; `disablePush` by default. */
  disable?: () => Promise<void>
  /** Turns one template off or on; `setPushPreference` by default. */
  save?: (template: string, subscribed: boolean) => Promise<void>
  className?: string
}

/**
 * Push notifications on this device and per kind (decision D69): a switch for this device (asks the browser's
 * permission, subscribes and registers it for the session) and one per template of the session's entry, as the mail
 * preferences. Says so instead when the system has push off or the browser cannot receive it.
 */
export function PushPreferences({
  load = pushSettings,
  supported = pushSupported,
  enabledHere = pushEnabledHere,
  enable = enablePush,
  disable = disablePush,
  save = setPushPreference,
  className,
}: PushPreferencesProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const listId = useId()
  const deviceId = useId()
  const [settings, setSettings] = useState<PushSettings | null>(null)
  const [here, setHere] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let live = true
    load()
      .then(async (loaded) => {
        const subscribed = await enabledHere(loaded).catch(() => false)
        if (!live) return
        setSettings(loaded)
        setHere(subscribed)
      })
      .catch((e: unknown) => live && setError(failure(e, t('pushPreferences.failed'))))
    return () => {
      live = false
    }
  }, [load, enabledHere, t])

  const toggleDevice = async (on: boolean) => {
    setBusy(true)
    setError(null)
    setDone(null)
    try {
      if (on) {
        const outcome = await enable()
        if (outcome === 'enabled') {
          setHere(true)
          setDone(t('pushPreferences.deviceOn'))
        } else {
          setError(t(`pushPreferences.${outcome}`))
        }
      } else {
        await disable()
        setHere(false)
        setDone(t('pushPreferences.deviceOff'))
      }
    } catch (e) {
      setError(failure(e, t('pushPreferences.failed')))
    } finally {
      setBusy(false)
    }
  }

  const toggle = async (template: Template, on: boolean) => {
    setBusy(true)
    setError(null)
    setDone(null)
    try {
      await save(template.template!, on)
      setSettings((current) => current && {
        ...current,
        templates: current.templates?.map((item) => item.template === template.template ? { ...item, subscribed: on } : item),
      })
      const name = template.name ?? template.template
      setDone(on ? t('pushPreferences.on', { name }) : t('pushPreferences.off', { name }))
    } catch (e) {
      setError(failure(e, t('pushPreferences.failed')))
    } finally {
      setBusy(false)
    }
  }

  if (!settings && !error) return <Spinner />
  const unavailable = settings && !settings.enabled ? t('pushPreferences.disabled')
    : !supported() ? t('pushPreferences.unsupported') : null
  return (
    <div className={cn('flex flex-col gap-4', className)} data-testid="push-preferences">
      <p className="text-muted-foreground text-sm">{t('pushPreferences.hint')}</p>
      {done && <Outcome ok testId="push-preferences-state">{done}</Outcome>}
      {error && <AuthError message={error} testId="push-preferences-error" />}
      {unavailable && (
        <p className="text-muted-foreground text-sm" data-testid="push-preferences-unavailable">{unavailable}</p>
      )}
      {settings && !unavailable && (
        <>
          <div className="flex items-center justify-between gap-4 rounded-md border p-3">
            <Label htmlFor={deviceId} className="font-normal">{t('pushPreferences.device')}</Label>
            <Switch id={deviceId} checked={here} disabled={busy} onCheckedChange={(on) => void toggleDevice(on)}
              data-testid="push-device" />
          </div>
          {(settings.templates?.length ?? 0) > 0 && (
            <ul className="divide-border flex flex-col divide-y rounded-md border" aria-label={t('pushPreferences.list')}
              data-testid="push-preferences-list">
              {settings.templates!.map((template) => {
                const id = `${listId}-${template.template}`
                return (
                  <li key={template.template} className="flex items-center justify-between gap-4 p-3"
                    data-testid="push-preference">
                    <Label htmlFor={id} className="font-normal">{template.name ?? template.template}</Label>
                    <Switch id={id} checked={template.subscribed ?? true} disabled={busy}
                      onCheckedChange={(on) => void toggle(template, on)} data-testid={`push-preference-${template.template}`} />
                  </li>
                )
              })}
            </ul>
          )}
        </>
      )}
    </div>
  )
}
