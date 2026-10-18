import { formatDateTime, useOptionalAuth } from '@jabiz/client'
import { Button, DescriptionList } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { forgetAttempts } from '../play/storage'
import { QB } from '../qb/messages'
import { navigate } from '../qb/router'

/** The app's version and when it was built, written by the build (vite.config.ts `define`). */
export const APP_VERSION = __QB_APP_VERSION__
export const APP_BUILT_AT = __QB_APP_BUILT_AT__

/**
 * About the app (U-74): its name, version and build date, and signing out. Readable without signing in; the frame of
 * the public pages links the help centre and the texts below it.
 */
export function AboutPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const auth = useOptionalAuth()
  return (
    <section className="flex flex-col gap-6" aria-labelledby="about-title" data-testid="about">
      <h1 id="about-title" className="text-xl font-semibold">{t('about.title', { name: tQb('brand') })}</h1>
      <p className="text-muted-foreground text-sm">{t('about.intro')}</p>
      <DescriptionList items={[
        { label: t('about.version'), value: <span data-testid="about-version">{APP_VERSION}</span> },
        { label: t('about.built'), value: <span data-testid="about-built">{formatDateTime(APP_BUILT_AT)}</span> },
      ]} />
      {auth?.signedIn && (
        <Button variant="outline" className="self-start" data-testid="about-sign-out"
          onClick={() => void auth.signOut().then(() => {
            forgetAttempts()
            navigate('/login', { replace: true })
          })}>
          {tQb('signOut')}
        </Button>
      )}
    </section>
  )
}
