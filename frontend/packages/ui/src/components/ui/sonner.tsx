import { CircleCheckIcon, InfoIcon, Loader2Icon, OctagonXIcon, TriangleAlertIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Toaster as Sonner, type ToasterProps } from 'sonner'

import { UI_SCOPE } from '../../lib/utils'
import { UI_NAMESPACE } from '../../i18n'
import { useAppearance } from '../appearance'

/** Where `notify` shows its messages; one per application, inside the AppearanceProvider. */
const Toaster = ({ ...props }: ToasterProps) => {
  const { resolved } = useAppearance()
  const { t } = useTranslation(UI_NAMESPACE)

  return (
    <Sonner
      theme={resolved}
      className={`${UI_SCOPE} toaster group`}
      containerAriaLabel={t('notify.notifications')}
      icons={{
        success: <CircleCheckIcon className="size-4" />,
        info: <InfoIcon className="size-4" />,
        warning: <TriangleAlertIcon className="size-4" />,
        error: <OctagonXIcon className="size-4" />,
        loading: <Loader2Icon className="size-4 animate-spin" />,
      }}
      style={
        {
          '--normal-bg': 'var(--popover)',
          '--normal-text': 'var(--popover-foreground)',
          '--normal-border': 'var(--border)',
          '--border-radius': 'var(--radius)',
        } as React.CSSProperties
      }
      {...props}
    />
  )
}

export { Toaster }
