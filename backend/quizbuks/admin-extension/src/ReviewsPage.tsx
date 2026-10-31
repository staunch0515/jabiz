import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  DataTable,
  EXTENSION_NAMESPACE,
  PageHeader,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { readPendingPublications, readPendingSponsors, type PublicationRow, type SponsorRow } from './api'
import { readPendingSponsorsAsOperator } from './operator-api'
import { EmptyState, LoadFailed, PublisherLink } from './components'
import { money, QUEUE, usePaged, when } from './hooks'
import { PATH, PERMISSION } from './ids'
import { readPendingSponsorPages } from './page-api'
import { SponsorPageQueue } from './SponsorPagesPage'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { name: 'name', submittedAt: 'waitingSince' } as const
/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS_2 = { company: 'company', submittedAt: 'submittedAt' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

function PublicationQueue() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { query, table, sortable } = usePaged(QUEUE.publications, readPendingPublications, true, SORTS)
  const columns = useMemo<ColumnDef<PublicationRow, unknown>[]>(() => [
    {
      id: 'name',
      header: t('reviews.name'),
      cell: ({ row }) => (
        <Link to={PATH.publication(row.original.publicationId)} className={linkClass}
          aria-label={t('reviews.openPublication', { name: row.original.name })} data-testid="queue-publication-link">
          {row.original.name}
        </Link>
      ),
    },
    {
      id: 'quiz',
      header: t('reviews.quiz'),
      cell: ({ row }) => {
        const { quizTitle, versionLabel, pendingVersionLabel } = row.original
        // Q51: a running publication asking to switch its version shows from which to which.
        return pendingVersionLabel
          ? <span data-testid="queue-publication-switch">
            {[quizTitle, t('reviews.switch', { from: versionLabel ?? '', to: pendingVersionLabel })]
              .filter(Boolean).join(' · ')}
          </span>
          : [quizTitle, versionLabel].filter(Boolean).join(' · ')
      },
    },
    { id: 'sponsor', header: t('reviews.sponsor'),
      cell: ({ row }) => <PublisherLink kind={row.original.publisherKind} id={row.original.publisherId} /> },
    { id: 'submittedAt', header: t('reviews.submittedAt'), cell: ({ row }) => when(row.original.waitingSince) },
    {
      id: 'budget',
      header: t('reviews.budget'),
      meta: { className: 'text-right tabular-nums' },
      cell: ({ row }) => money(row.original.budgetCap),
    },
  ], [t])
  if (query.isError) return <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
  return (
    <DataTable label={t('reviews.publicationsTable')} columns={sortable(columns)} {...table}
      getRowId={(row) => row.publicationId} empty={<EmptyState art="done" title={t('reviews.nonePublications')} />}
      rowProps={(row) => ({ 'data-testid': 'queue-publication', 'data-id': row.publicationId })} />
  )
}

/** Q48a: an operator reads the applications through its own template (it reads no user's profile). */
function useSponsorReader() {
  const { can } = useAuth()
  return !can(PERMISSION.usersRead) && can(PERMISSION.operatorUse) ? readPendingSponsorsAsOperator : readPendingSponsors
}

function SponsorQueue() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { query, table, sortable: sortable2 } = usePaged(QUEUE.sponsors, useSponsorReader(), true, SORTS_2)
  const columns = useMemo<ColumnDef<SponsorRow, unknown>[]>(() => [
    {
      id: 'company',
      header: t('reviews.company'),
      cell: ({ row }) => {
        const name = row.original.company ?? row.original.contactName
        return (
          <Link to={PATH.sponsor(row.original.userId)} className={linkClass}
            aria-label={t('reviews.openSponsor', { name: name ?? row.original.email ?? '' })}
            data-testid="queue-sponsor-link">
            {name ?? <UserName id={row.original.userId} />}
          </Link>
        )
      },
    },
    { id: 'contactName', header: t('reviews.contactName'), cell: ({ row }) => row.original.contactName ?? '' },
    { id: 'email', header: t('reviews.email'), cell: ({ row }) => row.original.email ?? '' },
    { id: 'submittedAt', header: t('reviews.submittedAt'), cell: ({ row }) => when(row.original.submittedAt) },
  ], [t])
  if (query.isError) return <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
  return (
    <DataTable label={t('reviews.sponsorsTable')} columns={sortable2(columns)} {...table}
      getRowId={(row) => row.onboardingId} empty={<EmptyState art="done" title={t('reviews.noneSponsors')} />}
      rowProps={(row) => ({ 'data-testid': 'queue-sponsor', 'data-id': row.userId })} />
  )
}

/** How many wait in a queue, as the server counted them (a request of one row). */
function useCount(key: readonly string[], read: (offset: number, limit: number) => Promise<{ total?: number }>,
  enabled: boolean) {
  const count = useQuery({ queryKey: [...key, 'count'], queryFn: () => read(0, 1), enabled })
  return count.data?.total == null ? '…' : count.data.total.toLocaleString()
}

/**
 * The review queue (02-design §7.3, P-09): submitted publications and sponsor applications that wait for a decision,
 * the longest waiting first, page by page with their counts. Each opens on a page that shows everything the decision
 * is about; the decision itself is taken there. A tab is offered to those who may review its kind; the server decides
 * either way.
 */
export default function ReviewsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const [search, setSearch] = useSearchParams()
  const publications = can(PERMISSION.reviewPublication)
  const sponsors = can(PERMISSION.reviewSponsor)
  const publicationCount = useCount(QUEUE.publications, readPendingPublications, publications)
  const sponsorCount = useCount(QUEUE.sponsors, useSponsorReader(), sponsors)
  // Q60a-3: sponsors' pages are reviewed by those who review sponsors, read through a template that needs the
  // publications' read permission as well.
  const pages = sponsors && can(PERMISSION.publicationRead)
  const pageCount = useCount(QUEUE.sponsorPages, readPendingSponsorPages, pages)
  const asked = search.get('tab')
  const tab = (asked === 'sponsors' && sponsors) || (asked === 'pages' && pages) ? asked
    : publications ? 'publications' : 'sponsors'
  return (
    <div className="flex flex-col gap-4" data-testid="qb-reviews">
      <PageHeader title={t('reviews.title')} description={t('reviews.description')} className="pb-0" />
      <Tabs value={tab} onValueChange={(value) => setSearch(value === 'publications' ? {} : { tab: value },
        { replace: true })}>
        <TabsList>
          {publications && (
            <TabsTrigger value="publications" data-testid="tab-publications">
              {t('reviews.publications', { n: publicationCount })}
            </TabsTrigger>
          )}
          {sponsors && (
            <TabsTrigger value="sponsors" data-testid="tab-sponsors">
              {t('reviews.sponsors', { n: sponsorCount })}
            </TabsTrigger>
          )}
          {pages && (
            <TabsTrigger value="pages" data-testid="tab-sponsor-pages">
              {t('sponsorPages.reviewsTab', { n: pageCount })}
            </TabsTrigger>
          )}
        </TabsList>
        {publications && <TabsContent value="publications"><PublicationQueue /></TabsContent>}
        {sponsors && <TabsContent value="sponsors"><SponsorQueue /></TabsContent>}
        {pages && <TabsContent value="pages"><SponsorPageQueue /></TabsContent>}
      </Tabs>
    </div>
  )
}
