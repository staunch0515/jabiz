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
import { PATH, PERMISSION } from './ids'
import { LoadFailed } from './components'
import { money, QUEUE, when } from './hooks'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'

function PublicationQueue() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const queue = useQuery({ queryKey: QUEUE.publications, queryFn: readPendingPublications })
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
      cell: ({ row }) => [row.original.quizTitle, row.original.versionLabel].filter(Boolean).join(' · '),
    },
    { id: 'sponsor', header: t('reviews.sponsor'), cell: ({ row }) => <UserName id={row.original.sponsorId} /> },
    { id: 'submittedAt', header: t('reviews.submittedAt'), cell: ({ row }) => when(row.original.submittedAt) },
    {
      id: 'budget',
      header: t('reviews.budget'),
      meta: { className: 'text-right tabular-nums' },
      cell: ({ row }) => money(row.original.budgetCap),
    },
  ], [t])
  if (queue.isError) return <LoadFailed error={queue.error} onRetry={() => void queue.refetch()} />
  return (
    <DataTable label={t('reviews.publicationsTable')} columns={columns} data={queue.data ?? []}
      loading={queue.isPending} getRowId={(row) => row.publicationId} empty={t('reviews.nonePublications')}
      rowProps={(row) => ({ 'data-testid': 'queue-publication', 'data-id': row.publicationId })} />
  )
}

function SponsorQueue() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const queue = useQuery({ queryKey: QUEUE.sponsors, queryFn: readPendingSponsors })
  const columns = useMemo<ColumnDef<SponsorRow, unknown>[]>(() => [
    {
      id: 'company',
      header: t('reviews.company'),
      cell: ({ row }) => {
        const name = row.original.company ?? row.original.contactName ?? row.original.userId
        return (
          <Link to={PATH.sponsor(row.original.userId)} className={linkClass}
            aria-label={t('reviews.openSponsor', { name })} data-testid="queue-sponsor-link">
            {name}
          </Link>
        )
      },
    },
    { id: 'contactName', header: t('reviews.contactName'), cell: ({ row }) => row.original.contactName ?? '' },
    { id: 'email', header: t('reviews.email'), cell: ({ row }) => row.original.email ?? '' },
    { id: 'submittedAt', header: t('reviews.submittedAt'), cell: ({ row }) => when(row.original.submittedAt) },
  ], [t])
  if (queue.isError) return <LoadFailed error={queue.error} onRetry={() => void queue.refetch()} />
  return (
    <DataTable label={t('reviews.sponsorsTable')} columns={columns} data={queue.data ?? []}
      loading={queue.isPending} getRowId={(row) => row.onboardingId} empty={t('reviews.noneSponsors')}
      rowProps={(row) => ({ 'data-testid': 'queue-sponsor', 'data-id': row.userId })} />
  )
}

/** The count of a queue for its tab, once known (shares the queue's query). */
function useCount(key: readonly string[], read: () => Promise<unknown[]>, enabled: boolean) {
  const queue = useQuery({ queryKey: key, queryFn: read, enabled })
  return queue.data ? String(queue.data.length) : '…'
}

/**
 * The review queue (02-design §7.3, P-09): submitted publications and sponsor applications that wait for a decision,
 * the longest waiting first. Each opens on a page that shows everything the decision is about; the decision itself is
 * taken there. A tab is offered to those who may review its kind; the server decides either way.
 */
export default function ReviewsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const [search, setSearch] = useSearchParams()
  const publications = can(PERMISSION.reviewPublication)
  const sponsors = can(PERMISSION.reviewSponsor)
  const publicationCount = useCount(QUEUE.publications, readPendingPublications, publications)
  const sponsorCount = useCount(QUEUE.sponsors, readPendingSponsors, sponsors)
  const tab = search.get('tab') === 'sponsors' && sponsors ? 'sponsors' : publications ? 'publications' : 'sponsors'
  return (
    <div className="flex flex-col gap-4" data-testid="qb-reviews">
      <PageHeader title={t('reviews.title')} description={t('reviews.description')} className="pb-0" />
      <Tabs value={tab} onValueChange={(value) => setSearch(value === 'sponsors' ? { tab: 'sponsors' } : {},
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
        </TabsList>
        {publications && <TabsContent value="publications"><PublicationQueue /></TabsContent>}
        {sponsors && <TabsContent value="sponsors"><SponsorQueue /></TabsContent>}
      </Tabs>
    </div>
  )
}
