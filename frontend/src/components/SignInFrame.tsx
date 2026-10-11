import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  cn,
  Input,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Spinner,
  UI_SCOPE,
} from '@jabiz/ui'
import type { LucideIcon } from 'lucide-react'
import { useId, type ComponentProps, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { changeLanguage, languages, type Language } from '../i18n'
import { LANGUAGE_NAMES } from '../i18n/languages'

/**
 * The frame of the pages before a session (signing in, and the pages of the mailed links: verifying an address,
 * joining, resetting a password): the application's name, the page's title and the language choice.
 */
export function SignInFrame({ subtitle, children, testId }: { subtitle: string; children: ReactNode; testId?: string }) {
  const { t, i18n } = useTranslation()
  return (
    <main className={cn(UI_SCOPE, 'bg-background text-foreground flex min-h-screen flex-col items-center px-4 pt-20')}>
      <Card className="w-full max-w-sm" data-testid={testId}>
        <CardHeader className="text-center">
          <CardTitle>
            <h1 className="text-2xl font-semibold tracking-tight">{t('app.title')}</h1>
          </CardTitle>
          <CardDescription>{subtitle}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">{children}</CardContent>
      </Card>
      {languages.length > 1 && (
        <div className="mt-4">
          <Select value={i18n.language} onValueChange={(lang) => void changeLanguage(lang as Language)}>
            <SelectTrigger size="sm" aria-label={t('app.language')}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {languages.map((lang) => (
                <SelectItem key={lang} value={lang} lang={lang}>
                  {LANGUAGE_NAMES[lang]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}
    </main>
  )
}

/** A text field of these forms: an icon, the placeholder as its name, and its error under it. */
export function SignInField({
  icon: Icon,
  error,
  ...input
}: { icon: LucideIcon; error?: string } & ComponentProps<'input'>) {
  const errorId = useId()
  return (
    <div className="flex flex-col gap-1.5">
      <div className="relative">
        <Icon aria-hidden className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2" />
        <Input
          {...input}
          aria-label={input.placeholder}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          className="h-10 pl-9"
        />
      </div>
      {error && (
        <p id={errorId} className="text-destructive text-sm">
          {error}
        </p>
      )}
    </div>
  )
}

export function SubmitButton({ busy, children }: { busy: boolean; children: ReactNode }) {
  return (
    <Button type="submit" size="lg" className="w-full" disabled={busy} aria-busy={busy || undefined}>
      {busy && <Spinner className="text-current" />}
      {children}
    </Button>
  )
}
