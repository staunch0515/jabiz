import { ApiError, formatDateTime, useAuth } from '@jabiz/client'
import { Alert, AlertDescription, Button, Checkbox, Label, MarkdownView } from '@jabiz/ui'
import { MutationCache, QueryCache, QueryClient, useMutation, useQuery, useQueryClient }
  from '@tanstack/react-query'
import { MailWarning } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { CODE } from './ids'
import { acceptConsents, legalPath, pickText, readMyConsents, type ConsentState,
  type LegalKind } from './legal'
import { QB } from './messages'
import { consentRequired, problemText } from './problems'
import { hrefOf } from './router'

/** The query of the caller's standing with the texts, which the pages that record consent refresh. */
export const CONSENTS_QUERY = ['qb', 'consents'] as const

/**
 * After a refusal for consent (`QB_CONSENT_REQUIRED`: a new version took effect since the standing was read), reads
 * the standing again so that the prompt comes up at once (review M3). For calls made outside React Query; the query
 * client of {@link newQbQueryClient} does it for every query and mutation.
 */
export function noticeConsentProblem(queryClient: QueryClient, error: unknown) {
  if (consentRequired(error)) void queryClient.invalidateQueries({ queryKey: CONSENTS_QUERY })
}

/** The query client of the SPAs: no retries, no refetch on focus, and any refusal for consent noticed. */
export function newQbQueryClient(): QueryClient {
  const client: QueryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
    queryCache: new QueryCache({ onError: (error) => noticeConsentProblem(client, error) }),
    mutationCache: new MutationCache({ onError: (error) => noticeConsentProblem(client, error) }),
  })
  return client
}

/** The caller's standing with the kinds; refreshed now and then (a new version may take effect meanwhile). */
export function useMyConsents(kinds: readonly LegalKind[], enabled = true) {
  return useQuery({
    queryKey: [...CONSENTS_QUERY, ...kinds],
    queryFn: () => readMyConsents(kinds),
    enabled,
    refetchInterval: 15 * 60_000,
  })
}

/**
 * A link to a text's page in a new tab (the prompt and the forms stay as they are); with a version, to that version
 * (the one the user is asked to accept, review L6), else to the one in effect when the page opens.
 */
export function LegalLink({ kind, versionNo, children, testId }: {
  kind: LegalKind
  versionNo?: number
  children?: ReactNode
  testId?: string
}) {
  const { t } = useTranslation(QB)
  const href = hrefOf(legalPath(kind)) + (versionNo ? `?v=${versionNo}` : '')
  return (
    <a href={href} target="_blank" rel="noopener" className="text-primary underline"
      data-testid={testId ?? `legal-link-${kind}`}>
      {children ?? t(`legal.kinds.${kind}`)}
    </a>
  )
}

/**
 * The full-screen prompt after signing in (docs/quizbuks/plans/Q7-messages-legal.md, D-Q7-5, Q7-2): while the caller
 * must accept a version of their texts, the pages are replaced by it: what changed, links to the full texts, "accept
 * and continue" (`QB_CONSENT_ACCEPT`) or sign out. The server refuses money and contract actions meanwhile whatever
 * the page shows; a caller whose address is not verified cannot accept (nothing they do writes), so they get a notice
 * above the pages instead. Without a version in effect, or when the standing cannot be read, the pages show.
 */
export function ConsentGate({ kinds, children, onSignOut, enabled = true }: {
  kinds: readonly LegalKind[]
  children: ReactNode
  onSignOut: () => void
  /** Off (the pages as they are) before the caller has anything to accept again: no profile or application yet. */
  enabled?: boolean
}) {
  const { emailVerified } = useAuth()
  const consents = useMyConsents(kinds, enabled)
  const due = enabled ? (consents.data ?? []).filter((row) => row.mustAccept) : []
  if (due.length === 0) return <>{children}</>
  if (!emailVerified) return <><UnverifiedNotice />{children}</>
  return <ConsentPrompt due={due} onSignOut={onSignOut} />
}

function UnverifiedNotice() {
  const { t } = useTranslation(QB)
  return (
    <Alert variant="warning" data-testid="consent-unverified">
      <MailWarning aria-hidden />
      <AlertDescription className="text-current">{t('consent.unverified')}</AlertDescription>
    </Alert>
  )
}

function ConsentPrompt({ due, onSignOut }: { due: ConsentState[]; onSignOut: () => void }) {
  const { t, i18n: { language } } = useTranslation(QB)
  const { signOut } = useAuth()
  const queryClient = useQueryClient()
  const id = useId()
  // What the user agreed to are these versions: when the server names newer ones (one took effect meanwhile, and the
  // acceptance was refused as outdated), they agree again, to what the prompt shows now (review M2).
  const shown = due.map((row) => `${row.kind}:${row.versionNo}`).join(',')
  const [agreed, setAgreed] = useState<string | null>(null)
  const accept = useMutation({
    mutationFn: () => acceptConsents(due.map((row) => ({ kind: row.kind, versionNo: row.versionNo }))),
    onSettled: () => queryClient.invalidateQueries({ queryKey: CONSENTS_QUERY }),
  })
  const outdated = accept.error instanceof ApiError
    && accept.error.violations.some((v) => v.ruleCode === CODE.termsOutdated)
  return (
    <main className="bg-background min-h-screen" aria-labelledby={`${id}-title`} data-testid="consent-gate">
      <div className="mx-auto flex w-full max-w-xl flex-col gap-5 px-4 py-8">
        <h1 id={`${id}-title`} className="text-xl font-semibold">{t('consent.title')}</h1>
        <p className="text-muted-foreground text-sm">{t('consent.intro')}</p>
        <ul className="flex flex-col gap-4">
          {due.map((row) => {
            const summary = pickText(row.changeSummary, language)
            return (
              <li key={row.kind} className="flex flex-col gap-2 rounded-md border p-4"
                data-testid={`consent-item-${row.kind}`}>
                <h2 className="font-semibold">
                  {t(`legal.kinds.${row.kind}`)}
                  <span className="text-muted-foreground ml-2 text-sm font-normal">
                    {t('legal.version', { n: row.versionNo })}
                  </span>
                </h2>
                <p className="text-muted-foreground text-sm">
                  {t('legal.effectiveSince', { time: formatDateTime(row.effectiveAt) })}
                </p>
                {summary.text && (
                  <div className="text-sm">
                    <p className="font-medium">{t('legal.changes')}</p>
                    <MarkdownView text={summary.text} lang={summary.lang} />
                  </div>
                )}
                <LegalLink kind={row.kind} versionNo={row.versionNo}>{t('consent.readFull')}</LegalLink>
              </li>
            )
          })}
        </ul>
        <div className="flex items-start gap-2">
          <Checkbox id={`${id}-agree`} checked={agreed === shown} data-testid="consent-agree"
            onCheckedChange={(checked) => setAgreed(checked === true ? shown : null)} />
          <Label htmlFor={`${id}-agree`} className="leading-snug">{t('consent.agree')}</Label>
        </div>
        {accept.isError && (
          <Alert variant="destructive" data-testid="consent-error">
            <AlertDescription>{outdated ? t('consent.outdated') : problemText(accept.error)}</AlertDescription>
          </Alert>
        )}
        <div className="flex flex-wrap gap-2">
          <Button disabled={agreed !== shown || accept.isPending} aria-busy={accept.isPending || undefined}
            data-testid="consent-accept" onClick={() => accept.mutate()}>
            {t('consent.accept')}
          </Button>
          <Button variant="outline" data-testid="consent-sign-out"
            onClick={() => void signOut().then(onSignOut)}>
            {t('signOut')}
          </Button>
        </div>
      </div>
    </main>
  )
}
