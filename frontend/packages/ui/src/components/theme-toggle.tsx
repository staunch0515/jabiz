import { MonitorIcon, MoonIcon, SunIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { APPEARANCES, useAppearance, type Appearance } from './appearance'
import { Button } from './ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from './ui/dropdown-menu'

const ICONS = { light: SunIcon, dark: MoonIcon, system: MonitorIcon } as const

/**
 * Light, dark or the system's appearance (decision D34 item 3), remembered in this browser. A menu of three radio
 * items; the button shows the current choice and is named "Appearance" in the interface language.
 */
export function ThemeToggle({ className }: { className?: string }) {
  const { t } = useTranslation(UI_NAMESPACE)
  const { appearance, setAppearance } = useAppearance()
  const Icon = ICONS[appearance]

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon-sm"
          className={className}
          aria-label={`${t('appearance.label')}: ${t(`appearance.${appearance}`)}`}
          data-testid="appearance-switch"
        >
          <Icon aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel>{t('appearance.label')}</DropdownMenuLabel>
        <DropdownMenuRadioGroup value={appearance} onValueChange={(value) => setAppearance(value as Appearance)}>
          {APPEARANCES.map((value) => {
            const ItemIcon = ICONS[value]
            return (
              <DropdownMenuRadioItem key={value} value={value}>
                <ItemIcon aria-hidden />
                {t(`appearance.${value}`)}
              </DropdownMenuRadioItem>
            )
          })}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
