import { changeLanguage, languages, LANGUAGE_NAMES, type Language } from '@jabiz/client'
import {
  Avatar,
  AvatarFallback,
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
  offerMailLanguage,
  useAppearance,
  type Appearance,
} from '@jabiz/ui'
import { CircleHelp, Languages, LogOut, Palette, ShieldCheck } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { navigate } from '../qb/router'
import './messages'

const APPEARANCES: readonly Appearance[] = ['light', 'dark', 'system']

/** The first letter of the name shown in the avatar (any script). */
const initialOf = (name: string | null | undefined) => Array.from(name?.trim() ?? '')[0]?.toUpperCase() ?? '?'

/**
 * The avatar menu at the top right (F-F3; Q108 section 2): the account and its security, the appearance, the
 * interface language, the help and signing out, out of the left-hand menu. Built from the platform's dropdown menu and
 * avatar; only the Sponsor app has one (the Takers' app drops its top-right menu, F-F4).
 */
export function AvatarMenu({ name, accountPath, helpPath, onSignOut }: {
  name: string | null | undefined
  accountPath: string
  helpPath: string
  onSignOut: () => void
}) {
  const { t, i18n } = useTranslation(SPONSOR)
  const { appearance, setAppearance } = useAppearance()
  const label = name ? t('shell.avatar.label', { name }) : t('shell.avatar.fallback')
  return (
    <DropdownMenu>
      <AvatarButton name={name} label={label} />
      <DropdownMenuContent align="end" className="min-w-56" data-testid="avatar-menu">
        <DropdownMenuLabel className="truncate" data-testid="sponsor-user">{name ?? t('shell.avatar.fallback')}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem data-testid="avatar-account" onSelect={() => navigate(accountPath)}>
          <ShieldCheck aria-hidden />
          {t('shell.avatar.account')}
        </DropdownMenuItem>
        <DropdownMenuSub>
          <DropdownMenuSubTrigger data-testid="avatar-appearance">
            <Palette aria-hidden className="text-muted-foreground mr-2 size-4" />
            {t('shell.avatar.appearance')}
          </DropdownMenuSubTrigger>
          <DropdownMenuSubContent>
            <DropdownMenuRadioGroup value={appearance} onValueChange={(value) => setAppearance(value as Appearance)}>
              {APPEARANCES.map((value) => (
                <DropdownMenuRadioItem key={value} value={value} data-testid={`avatar-appearance-${value}`}>
                  {t(`shell.avatar.appearances.${value}`)}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
          </DropdownMenuSubContent>
        </DropdownMenuSub>
        {languages.length > 1 && (
          <DropdownMenuSub>
            <DropdownMenuSubTrigger data-testid="avatar-language">
              <Languages aria-hidden className="text-muted-foreground mr-2 size-4" />
              {t('shell.avatar.language')}
            </DropdownMenuSubTrigger>
            <DropdownMenuSubContent>
              {/* As the account page's choice: the mail language is the account's and never changes silently (D49). */}
              <DropdownMenuRadioGroup value={i18n.language} onValueChange={(lang) => {
                void changeLanguage(lang as Language).then(() => offerMailLanguage(lang as Language))
              }}>
                {languages.map((lang) => (
                  <DropdownMenuRadioItem key={lang} value={lang} lang={lang} data-testid={`avatar-language-${lang}`}>
                    {LANGUAGE_NAMES[lang]}
                  </DropdownMenuRadioItem>
                ))}
              </DropdownMenuRadioGroup>
            </DropdownMenuSubContent>
          </DropdownMenuSub>
        )}
        <DropdownMenuItem data-testid="avatar-help" onSelect={() => navigate(helpPath)}>
          <CircleHelp aria-hidden />
          {t('shell.avatar.help')}
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem data-testid="sign-out" onSelect={onSignOut}>
          <LogOut aria-hidden />
          {t('shell.avatar.signOut')}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/** The avatar button that opens the menu, named with the account's name. */
function AvatarButton({ name, label }: { name: string | null | undefined; label: string }) {
  return (
    <DropdownMenuTrigger asChild>
      <Button variant="ghost" size="icon" className="rounded-full" aria-label={label} data-testid="avatar-menu-open">
        <Avatar className="size-8">
          <AvatarFallback aria-hidden>{initialOf(name)}</AvatarFallback>
        </Avatar>
      </Button>
    </DropdownMenuTrigger>
  )
}
