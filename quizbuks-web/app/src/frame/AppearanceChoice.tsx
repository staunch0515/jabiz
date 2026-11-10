import { APPEARANCES, Button, UI_NAMESPACE, useAppearance, type Appearance } from '@jabiz/ui'
import { Monitor, Moon, Sun, type LucideIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'

const ICONS: Record<Appearance, LucideIcon> = { light: Sun, dark: Moon, system: Monitor }

/**
 * Light, dark or following the system (platform `useAppearance`): the choice the top-right menu had until F-F4, now a
 * section of "App settings". Buttons that say which one is on (`aria-pressed`, and the filled one), not colour alone.
 */
export function AppearanceChoice() {
  const { t } = useTranslation(UI_NAMESPACE)
  const { appearance, setAppearance } = useAppearance()
  return (
    <div role="group" aria-label={t('appearance.label')} className="flex flex-wrap gap-2" data-testid="appearance-choices">
      {APPEARANCES.map((value) => {
        const Icon = ICONS[value]
        return (
          <Button key={value} type="button" size="sm" variant={appearance === value ? 'default' : 'outline'}
            aria-pressed={appearance === value} onClick={() => setAppearance(value)}
            data-testid={`appearance-${value}`}>
            <Icon aria-hidden />
            {t(`appearance.${value}`)}
          </Button>
        )
      })}
    </div>
  )
}
