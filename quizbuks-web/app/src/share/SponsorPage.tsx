import { readPageProducts } from './api'
import { ProductCards } from '../qb/view/ProductCards'
import { QBVIEW } from '../qb/view/messages'
import { formatDateTime, i18n, useAuth } from '@jabiz/client'
import { Button, MarkdownView, PageState, SegmentedControl } from '@jabiz/ui'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { ExternalLink, Trophy } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { FileImage } from '../qb/FileImage'
import { CoverPlaceholder } from '../qb/Illustrations'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { levelsOf } from './levels'
import { prerequisitesOf } from '../qb/play'
import { readPagePublications, readPublicSponsorPage, readSponsorTree } from './api'
import { Link, useLocation } from '../qb/router'
import { TechTree, TreeProgress, type TechTreeNode } from '../qb/TechTree'
import { certificationName, isSponsorCertification } from '../qb/certifications'
import { certificationNodeId, certificationOfNode, progressOf } from '../qb/tree'
import { readUnderstanding, UNDERSTANDING_QUERY } from '../qb/understanding'
import { sharePath } from './share'
import { countPageView, countProductClick } from './counting'
import { linkLanguageOf } from '../linkLanguage'

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
  // Q73c: in the language chosen on the page (a link's ?lang= first), else the interface's, else the page's main one.
  const [chosen, setChosen] = useState<string | null>(() => linkLanguageOf(window.location.pathname))
  const languages = [chosen, i18n.language.slice(0, 2)].filter((l, i, all): l is string => !!l && all.indexOf(l) === i)
  const page = useQuery({ queryKey: ['qb', 'sponsor-page', handle, languages],
    queryFn: () => readPublicSponsorPage(handle, languages), placeholderData: keepPreviousData })
  // Q60b: the page's listed products, in the sponsor's order.
  const products = useQuery({ queryKey: ['qb', 'sponsor-page-products', handle, languages],
    queryFn: () => readPageProducts(handle, languages), enabled: !!page.data, retry: false,
    placeholderData: keepPreviousData })
  const { t: tView } = useTranslation(QBVIEW)
  const publications = useQuery({ queryKey: ['qb', 'sponsor-page-publications', handle],
    queryFn: () => readPagePublications(handle), enabled: !!page.data })
  // Q85 (D-Q85-6): signed in, the tree as the caller stands on it (their own attempts), the picture by default;
  // the list stays for anybody. `?node=` marks a node (the result page's "what comes next").
  const { signedIn } = useAuth()
  const { search } = useLocation()
  const [at] = useState(() => new Date().toISOString())
  const lang = i18n.language.slice(0, 2)
  const tree = useQuery({ queryKey: ['qb', 'sponsor-tree', handle, lang],
    queryFn: () => readSponsorTree(handle, at, lang), enabled: signedIn && !!page.data })
  const [view, setView] = useState<'tree' | 'list'>('tree')
  const selected = new URLSearchParams(search).get('node')
  // Q86 (D-Q86-7): a certification required is a platform node; it leads to the platform's tree (D-Q86-4) when one is
  // named and is not this page, else to the certification's current round.
  const understanding = useQuery({ queryKey: [...UNDERSTANDING_QUERY, at], queryFn: () => readUnderstanding(at),
    enabled: signedIn && !!page.data, retry: false })
  const platformHandle = understanding.data?.[0]?.platformHandle ?? null
  const treeNodes: TechTreeNode[] = useMemo(() => {
    const rows = tree.data ?? []
    const certifications = new Map<string, { held: boolean; roundPublicationId: string | null;
      names: Record<string, string> | null }>()
    for (const row of rows) {
      for (const c of row.certifications) {
        const before = certifications.get(c.key)
        certifications.set(c.key, { held: c.held || !!before?.held,
          roundPublicationId: c.roundPublicationId ?? before?.roundPublicationId ?? null,
          names: c.names ?? before?.names ?? null })
      }
    }
    return [
      ...rows.map((row): TechTreeNode => ({ id: row.id, name: row.name, requires: row.requires, state: row.state,
        live: row.live, nodeKind: 'publication' })),
      // Q88a (D-Q88-4): a sponsor's own certification is a node of this sponsor's tree, named by its row.
      ...[...certifications.entries()].map(([key, c]): TechTreeNode => ({ id: certificationNodeId(key),
        name: certificationName(key, c.names, lang, tQb), requires: [], nodeKind: 'certification',
        state: c.held ? 'LIT' : 'LOCKED',
        subtitle: isSponsorCertification(key) ? tQb('certification.sponsorNode', { sponsor: page.data?.company ?? '' })
          : tQb('tree.certification') })),
    ]
  }, [tree.data, tQb, lang, page.data?.company])
  const rounds = useMemo(() => new Map((tree.data ?? []).flatMap((row) => row.certifications
    .filter((c) => c.roundPublicationId).map((c) => [c.key, c.roundPublicationId!]))), [tree.data])
  const hrefOf = (node: TechTreeNode): string | null => {
    const key = certificationOfNode(node.id)
    if (!key) return `/p/${encodeURIComponent(node.id)}`
    // A sponsor's own certification (Q88a) is earned here: its round, never the platform's tree.
    if (!isSponsorCertification(key) && platformHandle && platformHandle !== handle) {
      return `/s/${encodeURIComponent(platformHandle)}`
    }
    const round = rounds.get(key)
    return round ? `/p/${encodeURIComponent(round)}` : null
  }

  // Q60c: a view of a page that exists (published), once per page shown; the server counts a visitor once a day.
  const shown = page.data ? page.data.handle : null
  useEffect(() => {
    if (shown) countPageView(shown)
  }, [shown])

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
  const offered = String(p.languages ?? p.language).split(',').filter(Boolean)
  const shownIn = p.displayLanguage ?? p.language
  return (
    <article className="flex flex-col gap-6" aria-labelledby="sponsor-page-title" data-testid="sponsor-page">
      <header className="flex flex-col gap-3">
        {/* The cover meets the top bar: the page's top padding (main's py-4) is taken back. */}
        <div className="relative -mx-4 -mt-4 aspect-video overflow-hidden sm:mx-0 sm:border sm:border-t-0"
          data-testid="sponsor-page-cover-frame">
          {p.cover
            ? <FileImage fileId={p.cover} variant="w640" alt="" className="absolute inset-0 size-full object-cover"
              data-testid="sponsor-page-cover" />
            : <CoverPlaceholder seed={p.handle} className="absolute inset-0 size-full" />}
        </div>
        {offered.length > 1 && (
          <div className="flex flex-wrap items-center justify-end gap-1" role="group"
            aria-label={t('sponsorPage.language')} data-testid="sponsor-page-languages">
            {offered.map((language) => (
              <Button key={language} type="button" size="sm" lang={language}
                variant={language === shownIn ? 'secondary' : 'ghost'} aria-pressed={language === shownIn}
                data-testid={`sponsor-page-language-${language}`} onClick={() => setChosen(language)}>
                {tQb(`languages.${language}`)}
              </Button>
            ))}
          </div>
        )}
        <div className="flex items-center gap-3">
          {p.logo && <FileImage fileId={p.logo} variant="w256" alt="" className="size-14 shrink-0 rounded-full border object-cover"
            data-testid="sponsor-page-logo" />}
          <div className="flex min-w-0 flex-col">
            <h1 id="sponsor-page-title" className="text-xl font-semibold" data-testid="sponsor-page-company">{p.company}</h1>
            {/* The sponsor's own words are in the page's language; the rest is the interface's. */}
            <p className="text-muted-foreground text-sm" lang={shownIn} data-testid="sponsor-page-headline">
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
          <div lang={shownIn} data-testid="sponsor-page-story"><MarkdownView text={p.story} /></div>
        </section>
      )}
      <ProductCards products={products.data ?? []} title={tView('products.title')} testId="sponsor-page-products"
        onOpen={countProductClick} />
      <section aria-labelledby="sponsor-page-quizzes" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 id="sponsor-page-quizzes" className="text-lg font-semibold">{t('sponsorPage.quizzes')}</h2>
          {signedIn && tree.isSuccess && tree.data.length > 0 && (
            <SegmentedControl value={view} onChange={setView} aria-label={t('sponsorPage.quizzes')}
              data-testid="sponsor-page-view" options={[
                { value: 'tree', label: t('sponsorPage.tree') },
                { value: 'list', label: t('sponsorPage.list') },
              ]} />
          )}
        </div>
        {signedIn && view === 'tree' && tree.isSuccess && tree.data.length > 0 && (
          <div className="flex flex-col gap-2" data-testid="sponsor-page-tree">
            <TreeProgress lit={progressOf(treeNodes.filter((n) => n.nodeKind !== 'certification')).lit}
              total={tree.data.length}
              next={progressOf(treeNodes.filter((n) => n.nodeKind !== 'certification')).next.map((node) => node.name)} />
            <TechTree nodes={treeNodes} selected={selected} label={t('sponsorPage.treeLabel', { company: p.company })}
              hrefOf={hrefOf} />
            <p className="text-muted-foreground text-xs">{t('sponsorPage.treeHint')}</p>
          </div>
        )}
        {(!signedIn || view === 'list' || !tree.isSuccess || tree.data.length === 0) && <>
        {publications.isPending && <PageState kind="loading" />}
        {publications.isError && <PageState kind="error" description={tQb('failed')} />}
        {publications.isSuccess && publications.data.length === 0 && (
          <p className="text-muted-foreground text-sm" data-testid="sponsor-page-none">{t('sponsorPage.none')}</p>
        )}
        {publications.isSuccess && publications.data.length > 0 && levelsOf(publications.data).map((level, _i, all) => (
          // Q76 (D-Q76-8): the sponsor's levels, when its publications lead to one another; one level is a plain list.
          <div key={level.level} className="flex flex-col gap-2" data-testid="sponsor-page-level" data-level={level.level}>
            {all.length > 1 && (
              <h3 className="text-muted-foreground text-sm font-medium">{t('sponsorPage.level', { n: level.level })}</h3>
            )}
            <ul className="flex flex-col gap-2" data-testid="sponsor-page-publications">
              {level.items.map((publication) => {
                const names = prerequisitesOf(publication.prerequisites).map((p) => publications.data
                  .find((other) => other.publicationId === p.requiredPublicationId)?.name).filter(Boolean)
                return (
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
                        {names.length > 0 && (
                          <span data-testid="sponsor-page-after">{t('sponsorPage.after', { names: names.join(', ') })}</span>
                        )}
                      </span>
                    </Link>
                  </li>
                )
              })}
            </ul>
          </div>
        ))}
        </>}
      </section>
    </article>
  )
}
