import { i18n } from '@jabiz/client'
import { Alert, AlertDescription, Badge, Button, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { QuestionRow } from '../qb/answer/QuestionReview'
import { formatKudos } from '../qb/kudos'
import { minuteNow, pickText } from '../qb/legal'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { gateKey } from '../qb/tree/messages'
import { FEE_ACCOUNT_PATH } from '../shell/paths'
import { CHALLENGES_PATH, readChallengeResult, readChallenges, type Challenges } from './api'
import { CHALLENGES_QUERY } from './ChallengesPage'
import './messages'

/** While the attempt is being finished, or its pass is being turned into a lit node, asked again this often. */
const POLL_MS = 3_000

/** The node a publication is bound to, as the challenges read it: whether it is lit and what it opens and grants. */
function nodeOf(challenges: Challenges | undefined, publicationId: string) {
  for (const map of challenges?.maps ?? []) {
    for (const [nodeId, publications] of map.publications) {
      if (publications.some((p) => p.publicationId === publicationId)) {
        const node = map.graph.nodes.find((n) => n.nodeId === nodeId)
        return node ? { node, grant: map.grants.get(nodeId) ?? null } : null
      }
    }
  }
  return null
}

/**
 * A challenge's result (F-S4c): the score and whether it passed, the questions with the answers (the shared review),
 * and what the Sponsor got: the node lit with what it opens and the granted credit (its lot and expiry in the platform
 * fee account), or, not passed, to wait for the next challenge. Lighting runs after the answer: until the node is lit
 * the page says so and reads again.
 */
export function ChallengeResultPage({ attemptId }: { attemptId: string }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const result = useQuery({
    queryKey: ['qb', 'sponsor-challenge-result', attemptId],
    queryFn: () => readChallengeResult(attemptId),
    refetchOnMount: 'always',
    refetchInterval: (query) => (query.state.data?.status === 'IN_PROGRESS' ? POLL_MS : false),
  })
  const passed = result.data?.passed === true
  const challenges = useQuery({
    queryKey: [...CHALLENGES_QUERY, 'result', attemptId],
    queryFn: () => readChallenges(minuteNow()),
    enabled: passed,
    refetchInterval: (query) => {
      const found = result.data ? nodeOf(query.state.data, result.data.publicationId) : null
      return found && !found.node.lit ? POLL_MS : false
    },
  })
  if (result.isPending) return <PageState kind="loading" />
  if (result.isError) {
    return <PageState kind="error" description={problemText(result.error)}
      action={<Button variant="outline" onClick={() => void result.refetch()}>{tQb('retry')}</Button>} />
  }
  const data = result.data
  if (data.status === 'IN_PROGRESS') {
    return <PageState kind="loading" title={t('challenges.finishing')} data-testid="challenge-finishing" />
  }
  const byNo = new Map(data.questions.map((question) => [question.no, question]))
  const found = passed ? nodeOf(challenges.data, data.publicationId) : null
  const gates = (found?.node.gates ?? []).map(gateKey).filter((key): key is string => key !== null)
  return (
    <section className="flex flex-col gap-4" data-testid="challenge-result" data-passed={passed ? 'true' : 'false'}>
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="text-xl font-semibold">{data.name ?? t('challenges.resultTitle')}</h1>
        <Badge variant={passed ? 'default' : 'outline'} data-testid="challenge-passed">
          {passed ? t('challenges.passed') : t('challenges.notPassed')}
        </Badge>
      </div>
      {data.score != null && data.fullScore != null && (
        <p data-testid="challenge-score">{t('challenges.score', { score: data.score, full: data.fullScore })}</p>
      )}
      {!passed ? (
        <Alert data-testid="challenge-next"><AlertDescription>{t('challenges.waitNext')}</AlertDescription></Alert>
      ) : !found?.node.lit ? (
        <Alert data-testid="challenge-lighting"><AlertDescription>{t('challenges.lighting')}</AlertDescription></Alert>
      ) : (
        <Alert data-testid="challenge-gained">
          <AlertDescription className="flex flex-col gap-1">
            <span>{t('challenges.lit', { name: pickText(found.node.name, i18n.language).text })}</span>
            {gates.map((key) => <span key={key} data-testid="challenge-gate">{tQb(`tree.gates.${key}`)}</span>)}
            {found.grant !== null && (
              <span data-testid="challenge-grant">
                {t('challenges.grant', { amount: formatKudos(found.grant) })}{' '}
                <Link to={FEE_ACCOUNT_PATH} className="underline underline-offset-4">{t('challenges.toAccount')}</Link>
              </span>
            )}
          </AlertDescription>
        </Alert>
      )}
      <ol className="flex flex-col gap-2" aria-label={t('challenges.questions')}>
        {data.result.map((row, i) => (
          <QuestionRow key={row.no} result={row} question={byNo.get(row.no)} index={i} />
        ))}
      </ol>
      <div>
        <Button asChild variant="outline"><Link to={CHALLENGES_PATH}>{t('challenges.back')}</Link></Button>
      </div>
    </section>
  )
}
