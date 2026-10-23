import { LANGUAGE_NAMES, languages, register as registerAccount, type Language, type Registration } from '@jabiz/client'
import { Mail, User } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { useForm, useWatch, type UseFormReturn } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Button } from '../components/ui/button'
import { Label } from '../components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select'
import { AUTH_LINK_CLASS, AuthError, AuthField, AuthFrame, AuthLink, AuthSubmit, Outcome, type AuthNavigation } from './frame'
import { NewPasswordFields, ResendVerification, type NewPassword } from './MailLinkPage'
import { AUTH_NAMESPACE } from './messages'
import { failure, passwordProblems } from './problems'

/** The values of the sign-up form; an application's own fields (`extra`) add theirs. */
export type SignUpValues = NewPassword & { email: string; displayName: string; locale: string } & Record<string, unknown>

/**
 * What `onSubmitted` receives: everything entered but the passwords. Spelt out, since `Omit` of a type with an index
 * signature loses the named fields.
 */
export type SignUpSubmitted = { email: string; displayName: string; locale: string } & Record<string, unknown>

export interface SignUpFormProps extends AuthNavigation {
  /** The application's name, the page's heading. */
  brand: ReactNode
  appearance?: boolean
  /** The sign-in page, as a path of this frontend; no link when absent. */
  signInPath?: string
  /**
   * The application's own fields between the password and the button (say, consent to its terms), registered on the
   * form: a field whose rule fails stops the sign-up. They are not sent; `onSubmitted` receives them.
   */
  extra?: (form: UseFormReturn<SignUpValues>) => ReactNode
  /** Called once the server accepted the sign-up, with what was entered (not the password). */
  onSubmitted?: (values: SignUpSubmitted) => void
  /** Registers the account; `register` of `@jabiz/client` (the frontend's entry) by default. */
  submit?: (registration: Registration) => Promise<void>
}

/**
 * Signing up at the frontend's entry (docs/design/10-security.md section 15; decision D36): the address, the password
 * twice, the name and the language of the mails. The server answers alike whether the address is new, taken or
 * refused, so the form then always says to look in the mailbox ({@link SignUpSent}); the mail says what happened.
 */
export function SignUpForm({ brand, appearance, signInPath, extra, onSubmitted, submit = registerAccount, onNavigate, hrefOf }: SignUpFormProps) {
  const { t, i18n } = useTranslation(AUTH_NAMESPACE)
  const languageId = useId()
  const [sent, setSent] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const form = useForm<SignUpValues>({
    defaultValues: {
      email: '',
      displayName: '',
      password: '',
      confirm: '',
      locale: (languages as readonly string[]).includes(i18n.language) ? i18n.language : languages[0],
    },
  })
  const locale = useWatch({ control: form.control, name: 'locale' })
  const navigation = { onNavigate, hrefOf }

  if (sent !== null) {
    return <SignUpSent brand={brand} appearance={appearance} email={sent} signInPath={signInPath} {...navigation} />
  }

  const errors = form.formState.errors
  return (
    <AuthFrame brand={brand} appearance={appearance} title={t('signUp.title')} testId="sign-up">
      {error && <AuthError message={error} testId="sign-up-error" />}
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={form.handleSubmit(async (values) => {
          setError(null)
          setFieldError(null)
          const { password, confirm: _confirm, ...rest } = values
          void _confirm
          const email = values.email.trim()
          try {
            await submit({
              email,
              password,
              displayName: values.displayName.trim() || undefined,
              locale: values.locale || undefined,
            })
            onSubmitted?.({ ...rest, email, displayName: values.displayName, locale: values.locale })
            setSent(email)
          } catch (e) {
            if (passwordProblems(e).length > 0) setFieldError(passwordProblems(e).join(' '))
            else setError(failure(e, t('account.failed')))
          }
        })}
      >
        <AuthField
          icon={Mail}
          type="email"
          placeholder={t('account.email')}
          autoComplete="email"
          error={errors.email?.message}
          {...form.register('email', { validate: (v) => v.trim() !== '' || t('account.emailRequired') })}
        />
        <AuthField
          icon={User}
          placeholder={t('signUp.displayName')}
          autoComplete="name"
          {...form.register('displayName')}
        />
        <NewPasswordFields form={form as unknown as UseFormReturn<NewPassword>} serverError={fieldError} />
        {languages.length > 1 && (
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={languageId}>{t('signUp.language')}</Label>
            <Select value={locale} onValueChange={(lang) => form.setValue('locale', lang)}>
              <SelectTrigger id={languageId} className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {languages.map((lang: Language) => (
                  <SelectItem key={lang} value={lang} lang={lang}>
                    {LANGUAGE_NAMES[lang]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}
        {extra?.(form)}
        <AuthSubmit busy={form.formState.isSubmitting}>{t('signUp.submit')}</AuthSubmit>
      </form>
      {signInPath && (
        <AuthLink to={signInPath} {...navigation} className={AUTH_LINK_CLASS} data-testid="sign-in-link">
          {t('signUp.haveAccount')}
        </AuthLink>
      )}
    </AuthFrame>
  )
}

export interface SignUpSentProps extends AuthNavigation {
  brand: ReactNode
  appearance?: boolean
  /** The address the mail went to. */
  email: string
  /** The sign-in page, as a path of this frontend; no link when absent. */
  signInPath?: string
}

/**
 * After signing up (202): look in the mailbox. A new mail can be asked for (answered alike for every address), behind
 * "didn't get the mail?": offered at once, it was sent by people waiting for the first, whose link it used to void.
 */
export function SignUpSent({ brand, appearance, email, signInPath, onNavigate, hrefOf }: SignUpSentProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const [resending, setResending] = useState(false)
  return (
    <AuthFrame brand={brand} appearance={appearance} title={t('signUp.title')} testId="sign-up-sent">
      <Outcome ok testId="sign-up-state">
        {t('signUp.sent', { email })}
      </Outcome>
      <p className="text-muted-foreground text-sm">{t('signUp.sentHint')}</p>
      {resending ? <ResendVerification initialEmail={email} /> : (
        <Button variant="link" className="h-auto self-start p-0" data-testid="sign-up-no-mail"
          onClick={() => setResending(true)}>
          {t('signUp.noMail')}
        </Button>
      )}
      {signInPath && (
        <AuthLink to={signInPath} onNavigate={onNavigate} hrefOf={hrefOf} className={AUTH_LINK_CLASS}>
          {t('login.back')}
        </AuthLink>
      )}
    </AuthFrame>
  )
}
