import { formatDateTime } from '@jabiz/client'
import { Badge, Button, MarkdownView, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { Info } from 'lucide-react'
import { useState } from 'react'
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

/** The versions of a text, newest first, each linked to its full text. */
function History({ kind, rows, current, path }: {
  kind: LegalKind
  rows: LegalHistoryRow[]
  current: number | null
  /** The page's own path, which `?v=<n>` follows. */
  path: string
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
              <Link to={`${path}?v=${row.versionNo}`} className="text-primary self-start text-sm underline">
                {t('legal.read', { n: row.versionNo })}
              </Link>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

/**
 * A legal text's page (U-73, P-05; `/legal/<slug>`), readable without signing in: the version in effect now with
 * when it took effect, the version announced for later (with its date), and every version (`?v=<n>` shows one).
 * Only published versions exist for the public templates; a draft is never shown.
 */
export function LegalPage({ kind }: { kind: LegalKind }) {
  const { t, i18n: { language } } = useTranslation(QB)
  const { path, search } = useLocation()
  const asked = Number(new URLSearchParams(search).get('v')) || null
  const [at] = useState(minuteNow)
  const current = useQuery({ queryKey: ['qb', 'legal', kind, at], queryFn: () => readLegal(kind, at) })
  const version = useQuery({ queryKey: ['qb', 'legal-version', kind, asked], enabled: asked !== null,
    queryFn: () => readLegalVersion(kind, asked!) })
  const history = useQuery({ queryKey: ['qb', 'legal-history', kind], queryFn: () => readLegalHistory(kind) })
  const heading = t(`legal.kinds.${kind}`)

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
          {asked !== null && (
            <Link to={path} className="text-primary self-start text-sm underline">{t('legal.backToCurrent')}</Link>
          )}
          <LegalText body={shown.body} testId="legal-body" />
        </article>
      )
    }
  }
  const upcoming = current.data?.upcomingVersionNo
  return (
    <section className="flex flex-col gap-6" aria-labelledby="legal-title" data-testid="legal-page"
      data-kind={kind}>
      <h1 id="legal-title" className="text-xl font-semibold">{heading}</h1>
      {upcoming && current.data?.upcomingEffectiveAt && asked !== upcoming && (
        <div className="flex items-start gap-2 rounded-md border p-3 text-sm" data-testid="legal-upcoming">
          <Info aria-hidden className="mt-0.5 size-4 shrink-0" />
          <div className="flex flex-col items-start gap-1">
            <span>{t('legal.upcoming', { n: upcoming, time: formatDateTime(current.data.upcomingEffectiveAt) })}</span>
            <Link to={`${path}?v=${upcoming}`} className="text-primary underline">{t('legal.readUpcoming')}</Link>
          </div>
        </div>
      )}
      {content}
      {history.isSuccess && history.data.length > 0 && (
        <History kind={kind} rows={history.data} current={current.data?.versionNo ?? null} path={path} />
      )}
    </section>
  )
}
