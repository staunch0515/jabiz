import { i18n } from '@jabiz/client'
import { Alert, AlertDescription, Button, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { minuteNow } from '../qb/legal'
import { QB } from '../qb/messages'
import { Link } from '../qb/router'
import { KnowledgeMap, type Startable } from '../qb/tree/KnowledgeMap'
import { statesOf } from '../qb/tree/states'
import { challengeName, challengePath, challengeResultPath, readChallenges, type ChallengeMap } from './api'
import './messages'

export const CHALLENGES_QUERY = ['qb', 'sponsor-challenges'] as const

/**
 * The Sponsor's challenges (F-S4c, docs/quizbuks/plans/impl/F-S4.md §4): each copy of the Sponsor's operator drawn by
 * the shared map component, each node's state for the Sponsor (lit, locked, a challenge to answer, waiting for the
 * next one) with what lighting it opens and grants, and the challenges to start; below, the ones answered, each to its
 * result. What can be started is the server's one rule (`qb.sponsor.challenges`, `QB_SPONSOR_ATTEMPT_START`).
 */
export function ChallengesPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const challenges = useQuery({ queryKey: CHALLENGES_QUERY, queryFn: () => readChallenges(minuteNow()) })
  if (challenges.isPending) return <PageState kind="loading" />
  if (challenges.isError) {
    return <PageState kind="error" description={tQb('failed')}
      action={<Button variant="outline" onClick={() => void challenges.refetch()}>{tQb('retry')}</Button>} />
  }
  const { approved, maps } = challenges.data
  return (
    <div className="flex flex-col gap-6" data-testid="challenges-page">
      {maps.length > 0 && !approved && (
        <Alert data-testid="challenges-not-approved">
          <AlertDescription>{t('challenges.notApproved')}</AlertDescription>
        </Alert>
      )}
      {maps.length === 0
        ? <PageState kind="empty" title={t('challenges.none')} description={t('challenges.noneHint')} />
        : maps.map((map) => <ChallengeMapView key={map.graph.treeId} map={map} />)}
    </div>
  )
}

function ChallengeMapView({ map }: { map: ChallengeMap }) {
  const { t } = useTranslation(SPONSOR)
  const language = i18n.language
  const startable = useMemo(() => {
    const byNode = new Map<string, Startable[]>()
    for (const [nodeId, publications] of map.publications) {
      const open = publications.filter((p) => p.startable)
        .map((p) => ({ publicationId: p.publicationId, name: challengeName(p, language) }))
      if (open.length > 0) byNode.set(nodeId, open)
    }
    return byNode
  }, [map, language])
  const states = useMemo(() => statesOf(map.graph, new Set(startable.keys())), [map, startable])
  const grants = useMemo(() => new Map([...map.grants].map(([nodeId, amount]) => [nodeId, formatKudos(amount)])),
    [map])
  const answered = [...map.publications.values()].flat()
    .filter((p) => p.attemptId && p.status && p.status !== 'IN_PROGRESS')
  return (
    <section className="flex flex-col gap-3" data-testid="challenge-map" data-tree={map.graph.treeId}>
      <KnowledgeMap graph={map.graph} states={states} startable={startable} grants={grants}
        publicationLink={(publication) => (
          <Link to={challengePath(publication.publicationId)} className="text-primary underline underline-offset-4"
            data-testid="challenge-start" data-publication={publication.publicationId}>
            {t('challenges.start', { name: publication.name })}
          </Link>
        )} />
      {answered.length > 0 && (
        <div className="flex flex-col gap-1" data-testid="challenges-answered">
          <h3 className="text-sm font-medium">{t('challenges.answered')}</h3>
          <ul className="flex flex-col gap-1 text-sm">
            {answered.map((p) => (
              <li key={p.publicationId}>
                <Link to={challengeResultPath(p.attemptId!)} className="text-primary underline underline-offset-4"
                  data-testid="challenge-result-link">{challengeName(p, language)}</Link>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}
