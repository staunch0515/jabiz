import { ApiError, i18n, useAuth } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Button,
  Combobox,
  Input,
  Label,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck, MailWarning } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { browserHints, currencyOf, guessCountry } from '../country'
import { APP } from '../messages'
import { readCountries, readCurrencies, saveProfile, type Country, type DictionaryItem, type Profile,
  type ProfileOutput } from '../qb/api'
import { CONSENTS_QUERY, useMyConsents } from '../qb/ConsentGate'
import { CODE, LEGAL } from '../qb/ids'
import { QB } from '../qb/messages'
import { fieldProblem, generalProblem } from '../qb/problems'
import { TermsAgreement, acceptedVersions } from '../qb/TermsAgreement'

/** The query of the caller's profile, which the routes read too. */
export const PROFILE_QUERY = ['qb', 'profile'] as const

const FIELDS = ['displayName', 'countryCode', 'city', 'preferredCurrency', 'acceptTerms'] as const

/** A country's name in the interface language, else in English, else its code. */
function countryName(country: Country, language: string): string {
  return country.name[language] ?? country.name.en ?? country.code
}

/**
 * Says the address is not verified yet: QuizBuks writes need it (D-Q2-1), so the form cannot be saved. After following
 * the mailed link elsewhere, "I have verified it" asks for new tokens, which then say so.
 */
function VerifyFirst() {
  const { t } = useTranslation(QB)
  const { refreshSession, emailVerified } = useAuth()
  const [checked, setChecked] = useState(false)
  if (emailVerified) return null
  return (
    <Alert variant="warning" data-testid="profile-verify-first">
      <MailWarning aria-hidden />
      <AlertDescription className="text-current flex flex-col items-start gap-2">
        <span>{checked ? t('stillUnverified') : t('verifyFirst')}</span>
        <Button variant="outline" size="sm" data-testid="profile-check-verified"
          onClick={() => void refreshSession({ force: true }).then(() => setChecked(true))}>
          {t('checkAgain')}
        </Button>
      </AlertDescription>
    </Alert>
  )
}

/**
 * A taker's profile (U-02 – U-04, U-70; `QB_PROFILE_SAVE`): name, country, city and the currency amounts are compared
 * in. The first save completes registration (the welcome page); later ones edit it (the account page), refused when
 * somebody changed it meanwhile. A new profile starts from the name given at sign-up and the browser's country.
 */
export function ProfileForm({ profile, submitLabel, onSaved, consent = false, brief = false }: {
  profile: Profile | null
  submitLabel: string
  onSaved?: (output: ProfileOutput) => void
  /** Registration's second step: the terms and the privacy policy in effect are accepted with it (Q7-3). */
  consent?: boolean
  /**
   * The short step after a share link (Q15): the country (and the terms) only; the name is the one signed up with,
   * else the address's part before the @, and the currency the country's. Both can be changed on the account page.
   */
  brief?: boolean
}) {
  const countries = useQuery({ queryKey: ['qb', 'countries'], queryFn: readCountries, staleTime: Infinity })
  const currencies = useQuery({ queryKey: ['qb', 'currencies', i18n.language], queryFn: readCurrencies })
  const { t } = useTranslation(QB)
  if (countries.isError || currencies.isError) return <PageState kind="error" description={t('failed')} />
  if (!countries.data || !currencies.data) return <PageState kind="loading" />
  return <ProfileFields profile={profile} countries={countries.data} currencies={currencies.data}
    submitLabel={submitLabel} onSaved={onSaved} consent={consent} brief={brief} />
}

/** A name from an address: the part before the @, at most the profile's length. */
export function nameFromEmail(email: string | null | undefined): string {
  const local = (email ?? '').split('@')[0]?.trim() ?? ''
  return local.slice(0, 100)
}

function ProfileFields({ profile, countries, currencies, submitLabel, onSaved, consent, brief }: {
  profile: Profile | null
  countries: Country[]
  currencies: DictionaryItem[]
  submitLabel: string
  onSaved?: (output: ProfileOutput) => void
  consent: boolean
  brief: boolean
}) {
  const { t, i18n: { language } } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const { displayName: signedUpAs, email, emailVerified } = useAuth()
  const queryClient = useQueryClient()
  const id = useId()
  // The short step shows no name: never the sign-in name when it is the address itself (an account a mailed link
  // opened has none other), which would then be shown to sponsors.
  const [displayName, setDisplayName] = useState(() => profile?.displayName
    || (brief ? (signedUpAs && !signedUpAs.includes('@') ? signedUpAs : nameFromEmail(email)) : signedUpAs || ''))
  const [countryCode, setCountryCode] = useState<string | null>(() => {
    if (profile?.countryCode) return profile.countryCode
    const { languages, timeZone } = browserHints()
    return guessCountry(languages, timeZone, new Set(countries.map((c) => c.code)))
  })
  const [city, setCity] = useState(() => profile?.city ?? '')
  const [currency, setCurrency] = useState(() => profile?.preferredCurrency
    ?? currencyOf(countryCode, currencies.map((c) => c.code)))
  const [saved, setSaved] = useState(false)
  const [acceptTerms, setAcceptTerms] = useState(false)
  // The texts in effect, accepted with the first save (none in effect: nothing to accept).
  const terms = useMyConsents(LEGAL.appKinds, consent && emailVerified)
  const asksTerms = consent && (terms.data?.length ?? 0) > 0

  const save = useMutation({
    mutationFn: () => saveProfile({
      displayName: displayName.trim(),
      countryCode: countryCode ?? '',
      city: city.trim() === '' ? null : city.trim(),
      // The short step shows no currency: the country's.
      preferredCurrency: brief ? currencyOf(countryCode, currencies.map((c) => c.code)) : currency,
      baseRevision: profile?.revision ?? null,
      ...(asksTerms ? { acceptTerms, ...acceptedVersions(terms.data) } : {}),
    }),
    onSuccess: async (output) => {
      setSaved(true)
      // The consents first: once the profile is complete, the prompt reads them, and must not see the old ones.
      await queryClient.invalidateQueries({ queryKey: CONSENTS_QUERY })
      await queryClient.invalidateQueries({ queryKey: PROFILE_QUERY })
      onSaved?.(output)
    },
    // A text changed meanwhile: read it again, and the agreement with it.
    onError: (error) => {
      if (error instanceof ApiError && error.violations.some((v) => v.ruleCode === CODE.termsOutdated)) {
        setAcceptTerms(false)
        void queryClient.invalidateQueries({ queryKey: CONSENTS_QUERY })
      }
    },
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setSaved(false)
    save.mutate()
  }
  const problem = (field: string) => fieldProblem(save.error, field)
  const general = generalProblem(save.error, FIELDS)
  const options = countries
    .map((c) => ({ value: c.code, label: countryName(c, language) }))
    .sort((a, b) => a.label.localeCompare(b.label, language))

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4" data-testid="profile-form">
      <VerifyFirst />
      {(!brief || problem('displayName')) && <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-name`}>{t('profile.displayName')}</Label>
        <Input id={`${id}-name`} value={displayName} required maxLength={100} autoComplete="nickname"
          aria-invalid={problem('displayName') ? true : undefined}
          aria-describedby={problem('displayName') ? `${id}-name-error` : undefined}
          data-testid="profile-name" onChange={(event) => setDisplayName(event.target.value)} />
        {problem('displayName') && (
          <p id={`${id}-name-error`} className="text-destructive text-sm">{problem('displayName')}</p>
        )}
      </div>}
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-country`}>{t('profile.country')}</Label>
        <Combobox id={`${id}-country`} value={countryCode} onChange={setCountryCode} options={options} required
          placeholder={t('profile.chooseCountry')} searchPlaceholder={t('profile.searchCountry')}
          emptyText={t('profile.noCountry')} invalid={!!problem('countryCode')}
          aria-describedby={problem('countryCode') ? `${id}-country-error` : undefined} data-testid="profile-country" />
        {problem('countryCode') && (
          <p id={`${id}-country-error`} className="text-destructive text-sm">{problem('countryCode')}</p>
        )}
      </div>
      {!brief && <>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-city`}>{t('profile.city')}</Label>
        <Input id={`${id}-city`} value={city} maxLength={100} autoComplete="address-level2"
          aria-invalid={problem('city') ? true : undefined}
          aria-describedby={problem('city') ? `${id}-city-error` : undefined} data-testid="profile-city"
          onChange={(event) => setCity(event.target.value)} />
        {problem('city') && <p id={`${id}-city-error`} className="text-destructive text-sm">{problem('city')}</p>}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-currency`}>{t('profile.currency')}</Label>
        <Select value={currency} onValueChange={setCurrency}>
          <SelectTrigger id={`${id}-currency`} data-testid="profile-currency"
            aria-invalid={problem('preferredCurrency') ? true : undefined}
            aria-describedby={problem('preferredCurrency') ? `${id}-currency-hint ${id}-currency-error`
              : `${id}-currency-hint`}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {currencies.map((c) => (
              <SelectItem key={c.code} value={c.code}>{c.label}</SelectItem>
            ))}
          </SelectContent>
        </Select>
        <p id={`${id}-currency-hint`} className="text-muted-foreground text-sm">{t('profile.currencyHint')}</p>
        {problem('preferredCurrency') && (
          <p id={`${id}-currency-error`} className="text-destructive text-sm">{problem('preferredCurrency')}</p>
        )}
      </div>
      </>}
      {asksTerms && (
        <div className="flex flex-col gap-2">
          <TermsAgreement id={`${id}-terms`} kinds={LEGAL.appKinds} rows={terms.data ?? []} checked={acceptTerms}
            onChange={setAcceptTerms} invalid={problem('acceptTerms') ? true : undefined}
            describedBy={problem('acceptTerms') ? `${id}-terms-error` : undefined} testId="profile-terms" />
          {problem('acceptTerms') && (
            <p id={`${id}-terms-error`} className="text-destructive text-sm">{problem('acceptTerms')}</p>
          )}
        </div>
      )}
      {general && (
        <Alert variant="destructive" data-testid="profile-error">
          <AlertDescription>{general}</AlertDescription>
        </Alert>
      )}
      {saved && !save.isPending && (
        <p role="status" className="text-success flex items-center gap-2 text-sm" data-testid="profile-saved">
          <CircleCheck aria-hidden className="size-4" />
          {tQb('saved')}
        </p>
      )}
      <Button type="submit" disabled={save.isPending || !emailVerified || !countryCode
        || (consent && terms.isPending && emailVerified) || (asksTerms && !acceptTerms)}
        aria-busy={save.isPending || undefined} data-testid="profile-save">
        {submitLabel}
      </Button>
    </form>
  )
}
