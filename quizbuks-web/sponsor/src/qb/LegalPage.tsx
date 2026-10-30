import { formatDateTime } from '@jabiz/client'
import { Badge, Button, MarkdownView, PageState, Sheet, SheetContent, SheetHeader, SheetTitle } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { Info } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { pickText, readLegal, readLegalHistory, readLegalVersion, minuteNow, type I18nText, type LegalHistoryRow,
  type LegalKind } from './legal'
import { QB } from './messages'
import { Link, useLocation } from './router'

/**
 * A legal text in the reader's language: Japanese governs (Q7-4), so any other language says so and offers the
 * Japanese text; a language the text lacks (Chinese) shows the English one and says that too. Markdown only through
 * `MarkdownView` (no raw HTML).
 */
export function LegalText({ body, testId }: { body: I18nText; testId?: string }) {
  const { t, i18n: { language } } = useTranslation(QB)
  const [original, setOriginal] = useState(false)
  const translated = pickText(body, language)
  const shown = original && body.ja?.trim() ? { text: body.ja, lang: language.startsWith('ja') ? undefined : 'ja' }
    : translated
  const japanese = language.startsWith('ja')
  return (
    <div className="flex flex-col gap-3" data-testid={testId}>
      {!japanese && (
        <div className="bg-muted text-muted-foreground flex items-start gap-2 rounded-md p-3 text-sm"
          data-testid="legal-governing">
          <Info aria-hidden className="mt-0.5 size-4 shrink-0" />
          <div className="flex flex-col items-start gap-1">
            {translated.lang === 'en' && !original && (
              <span data-testid="legal-in-english">{t('legal.shownInEnglish')}</span>
            )}
            <span>{t('legal.governing')}</span>
            {body.ja?.trim() && (
              <Button variant="link" className="h-auto p-0" data-testid="legal-original"
                onClick={() => setOriginal(!original)}>
                {original ? t('legal.showTranslation') : t('legal.showJapanese')}
              </Button>
            )}
          </div>
        </div>
      )}
      <MarkdownView text={shown.text} lang={shown.lang} />
    </div>
  )
}

/**
 * How a legal text leads to one of its versions (null: the one in effect): a link to `?v=<n>` on its page, a button in
 * the sheet that shows it over a form.
 */
type VersionLink = (versionNo: number | null, children: ReactNode, className: string) => ReactNode

/** The versions of a text, newest first, each linked to its full text. */
function History({ kind, rows, current, versionLink }: {
  kind: LegalKind
  rows: LegalHistoryRow[]
  current: number | null
  versionLink: VersionLink
}) {
  const { t, i18n: { language } } = useTranslation(QB)
  return (
    <section className="flex flex-col gap-3" aria-labelledby={`history-${kind}`} data-testid="legal-history">
      <h2 id={`history-${kind}`} className="text-lg font-semibold">{t('legal.history')}</h2>
      <ul className="flex flex-col gap-3">
        {rows.map((row) => {
          const summary = pickText(row.changeSummary, language)
          const announced = !row.withdrawn && (current === null || row.versionNo > current)
          return (
            <li key={row.versionNo} className="flex flex-col gap-1 rounded-md border p-3"
              data-testid={`legal-history-${row.versionNo}`}>
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <span className="font-medium">{t('legal.version', { n: row.versionNo })}</span>
                {row.versionNo === current && <Badge variant="success">{t('legal.inEffect')}</Badge>}
                {announced && <Badge variant="secondary">{t('legal.announced')}</Badge>}
                {row.withdrawn && (
                  <Badge variant="outline" data-testid="legal-withdrawn">{t('legal.withdrawn')}</Badge>
                )}
                <span className="text-muted-foreground">
                  {t(row.withdrawn ? 'legal.wasToTakeEffect' : announced ? 'legal.effectiveFrom'
                    : 'legal.effectiveSince', { time: formatDateTime(row.effectiveAt) })}
                </span>
              </div>
              {summary.text && (
                <div className="text-sm">
                  <MarkdownView text={summary.text} lang={summary.lang} />
                </div>
              )}
              {versionLink(row.versionNo, t('legal.read', { n: row.versionNo }),
                'text-primary self-start text-sm underline')}
            </li>
          )
        })}
      </ul>
    </section>
  )
}

/**
 * A legal text: the version asked for, else the one in effect now, with when it took effect, the version announced for
 * later (with its date), and every version. Only published versions exist for the public templates; a draft is never
 * shown. Which version shows is the caller's: the page's address, or the sheet's own state.
 */
function LegalDocument({ kind, asked, versionLink, heading }: {
  kind: LegalKind
  asked: number | null
  versionLink: VersionLink
  /** The page's own heading; none in the sheet, whose title names the text. */
  heading: boolean
}) {
  const { t, i18n: { language } } = useTranslation(QB)
  const [at] = useState(minuteNow)
  const current = useQuery({ queryKey: ['qb', 'legal', kind, at], queryFn: () => readLegal(kind, at) })
  const version = useQuery({ queryKey: ['qb', 'legal-version', kind, asked], enabled: asked !== null,
    queryFn: () => readLegalVersion(kind, asked!) })
  const history = useQuery({ queryKey: ['qb', 'legal-history', kind], queryFn: () => readLegalHistory(kind) })

  let content
  if (current.isPending || (asked !== null && version.isPending)) content = <PageState kind="loading" />
  else if (current.isError || version.isError) content = <PageState kind="error" description={t('failed')} />
  else {
    const shown = asked !== null ? version.data : current.data?.versionNo ? current.data : null
    const currentNo = current.data?.versionNo ?? null
    if (!shown || shown.versionNo === null || !shown.body) {
      content = <PageState kind="empty" description={t('legal.none')} />
    } else {
      const title = pickText(shown.title, language)
      const withdrawn = 'withdrawn' in shown && shown.withdrawn
      const isAnnounced = currentNo === null || shown.versionNo > currentNo
      content = (
        <article className="flex flex-col gap-3" data-testid="legal-text" data-version={shown.versionNo}>
          <h2 className="text-lg font-semibold" lang={title.lang}>{title.text}</h2>
          <p className="text-muted-foreground text-sm" data-testid="legal-version">
            {t('legal.version', { n: shown.versionNo })}
            {' · '}
            {t(withdrawn ? 'legal.wasToTakeEffect' : isAnnounced ? 'legal.effectiveFrom' : 'legal.effectiveSince',
              { time: formatDateTime(shown.effectiveAt) })}
          </p>
          {withdrawn && (
            <p className="bg-muted rounded-md p-3 text-sm" data-testid="legal-withdrawn-note">
              {t('legal.withdrawnNote')}
            </p>
          )}
          {asked !== null && versionLink(null, t('legal.backToCurrent'), 'text-primary self-start text-sm underline')}
          <LegalText body={shown.body} testId="legal-body" />
        </article>
      )
    }
  }
  const upcoming = current.data?.upcomingVersionNo
  return (
    <section className="flex flex-col gap-6" aria-labelledby={heading ? 'legal-title' : undefined}
      data-testid="legal-page" data-kind={kind}>
      {heading && <h1 id="legal-title" className="text-xl font-semibold">{t(`legal.kinds.${kind}`)}</h1>}
      {upcoming && current.data?.upcomingEffectiveAt && asked !== upcoming && (
        <div className="flex items-start gap-2 rounded-md border p-3 text-sm" data-testid="legal-upcoming">
          <Info aria-hidden className="mt-0.5 size-4 shrink-0" />
          <div className="flex flex-col items-start gap-1">
            <span>{t('legal.upcoming', { n: upcoming, time: formatDateTime(current.data.upcomingEffectiveAt) })}</span>
            {versionLink(upcoming, t('legal.readUpcoming'), 'text-primary underline')}
          </div>
        </div>
      )}
      {content}
      {history.isSuccess && history.data.length > 0 && (
        <History kind={kind} rows={history.data} current={current.data?.versionNo ?? null} versionLink={versionLink} />
      )}
    </section>
  )
}

/**
 * A legal text's page (U-73, P-05; `/legal/<slug>`), readable without signing in; `?v=<n>` shows one version.
 */
export function LegalPage({ kind }: { kind: LegalKind }) {
  const { path, search } = useLocation()
  const asked = Number(new URLSearchParams(search).get('v')) || null
  return (
    <LegalDocument kind={kind} asked={asked} heading
      versionLink={(versionNo, children, className) => (
        <Link to={versionNo === null ? path : `${path}?v=${versionNo}`} className={className}>{children}</Link>
      )} />
  )
}

/**
 * A legal text over the page, for the forms that ask to accept it (sign-up, the welcome step, a new version to
 * accept): the form stays as it was, and nothing opens another window. In an app installed to the home screen or the
 * desktop, a new window has no way back but closing it.
 */
export function LegalSheet({ kind, versionNo, open, onOpenChange }: {
  kind: LegalKind
  /** The version to show first (the one the user is asked to accept); the one in effect when absent. */
  versionNo?: number
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation(QB)
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-lg" data-testid="legal-sheet">
        <SheetHeader>
          <SheetTitle>{t(`legal.kinds.${kind}`)}</SheetTitle>
        </SheetHeader>
        <div className="px-4 pb-6">
          {/* Mounted on each opening: it starts again from the version the link names. */}
          {open && <SheetDocument kind={kind} versionNo={versionNo} />}
        </div>
      </SheetContent>
    </Sheet>
  )
}

function SheetDocument({ kind, versionNo }: { kind: LegalKind; versionNo?: number }) {
  const [asked, setAsked] = useState<number | null>(versionNo ?? null)
  return (
    <LegalDocument kind={kind} asked={asked} heading={false}
      versionLink={(next, children, className) => (
        <Button variant="link" className={`h-auto p-0 ${className}`} onClick={() => setAsked(next)}>
          {children}
        </Button>
      )} />
  )
}
