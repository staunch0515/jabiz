import { formatDateTime } from '@jabiz/client'
import { MarkdownView, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { ExternalLink, Trophy } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { FileImage } from '../qb/FileImage'
import { CoverPlaceholder } from '../qb/Illustrations'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { readPagePublications, readPublicSponsorPage } from '../qb/page'
import { Link } from '../qb/router'
import { sharePath } from './share'

const SPONSOR_PAGE = /^\/s\/([^/]+)$/

/** The handle of a sponsor's page path (`/s/<handle>`), in lowercase as stored, or null for any other path. */
export function sponsorPageOf(path: string): string | null {
  const match = SPONSOR_PAGE.exec(path)
  if (!match) return null
  try {
    return decodeURIComponent(match[1]).toLowerCase()
  } catch {
    return null
  }
}

/** Only an https link is ever made a link (the server accepts no other, this is the second line). */
function httpsUrl(url: string | null): string | null {
  return url && /^https:\/\/[^\s"'<>]+$/.test(url) ? url : null
}

/**
 * A sponsor's page (Q60a; docs/quizbuks/plans/Q60-sponsor-page.md), for anybody, signed in or not: the cover, the
 * logo, the company, the headline, the story (Markdown, no HTML), the website, and the sponsor's quizzes open now,
 * each leading to its own page (the share page before signing in). From the public templates only.
 */
export function SponsorPage({ handle }: { handle: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const page = useQuery({ queryKey: ['qb', 'sponsor-page', handle], queryFn: () => readPublicSponsorPage(handle) })
  const publications = useQuery({ queryKey: ['qb', 'sponsor-page-publications', handle],
    queryFn: () => readPagePublications(handle), enabled: !!page.data })

  if (page.isPending) return <PageState kind="loading" />
  if (page.isError) return <PageState kind="error" description={tQb('failed')} />
  const p = page.data
  if (!p) {
    return (
      <PageState kind="notFound" titleAs="h1" title={t('sponsorPage.notFound')} description={t('sponsorPage.notFoundHint')}
        action={<Link to="/" className="text-primary text-sm underline" data-testid="sponsor-page-home">
          {t('sponsorPage.home')}</Link>} />
    )
  }
  const website = httpsUrl(p.website)
  return (
    <article className="flex flex-col gap-6" aria-labelledby="sponsor-page-title" data-testid="sponsor-page">
      <header className="flex flex-col gap-3">
        <div className="relative -mx-4 aspect-video overflow-hidden sm:mx-0 sm:border">
          {p.cover
            ? <FileImage fileId={p.cover} variant="w640" alt="" className="absolute inset-0 size-full object-cover"
              data-testid="sponsor-page-cover" />
            : <CoverPlaceholder seed={p.handle} className="absolute inset-0 size-full" />}
        </div>
        <div className="flex items-center gap-3">
          {p.logo && <FileImage fileId={p.logo} variant="w256" alt="" className="size-14 shrink-0 rounded-full border object-cover"
            data-testid="sponsor-page-logo" />}
          <div className="flex min-w-0 flex-col">
            <h1 id="sponsor-page-title" className="text-xl font-semibold" data-testid="sponsor-page-company">{p.company}</h1>
            {/* The sponsor's own words are in the page's language; the rest is the interface's. */}
            <p className="text-muted-foreground text-sm" lang={p.language} data-testid="sponsor-page-headline">
              {p.headline}</p>
          </div>
        </div>
        {website && (
          <a href={website} target="_blank" rel="noopener noreferrer nofollow ugc"
            className="text-primary inline-flex items-center gap-1 self-start text-sm underline break-all"
            data-testid="sponsor-page-website">
            <ExternalLink aria-hidden className="size-4 shrink-0" />{t('sponsorPage.website')}
          </a>
        )}
      </header>
      {p.story && (
        <section aria-labelledby="sponsor-page-story" className="flex flex-col gap-2">
          <h2 id="sponsor-page-story" className="text-lg font-semibold">{t('sponsorPage.story')}</h2>
          <div lang={p.language} data-testid="sponsor-page-story"><MarkdownView text={p.story} /></div>
        </section>
      )}
      <section aria-labelledby="sponsor-page-quizzes" className="flex flex-col gap-3">
        <h2 id="sponsor-page-quizzes" className="text-lg font-semibold">{t('sponsorPage.quizzes')}</h2>
        {publications.isPending && <PageState kind="loading" />}
        {publications.isError && <PageState kind="error" description={tQb('failed')} />}
        {publications.isSuccess && publications.data.length === 0 && (
          <p className="text-muted-foreground text-sm" data-testid="sponsor-page-none">{t('sponsorPage.none')}</p>
        )}
        {publications.isSuccess && publications.data.length > 0 && (
          <ul className="flex flex-col gap-2" data-testid="sponsor-page-publications">
            {publications.data.map((publication) => (
              <li key={publication.publicationId}>
                <Link to={sharePath(publication.publicationId)}
                  className="bg-card hover:bg-accent flex flex-col gap-1 border p-3"
                  data-testid="sponsor-page-publication">
                  <span className="font-medium">{publication.name}</span>
                  <span className="text-muted-foreground flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                    <span className="inline-flex items-center gap-1">
                      <Trophy aria-hidden className="size-3.5" />
                      {t('sponsorPage.upTo', { amount: formatKudos(publication.maxReward) })}
                    </span>
                    <span>{t('sponsorPage.endsAt', { time: formatDateTime(publication.endAt) })}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </article>
  )
}
