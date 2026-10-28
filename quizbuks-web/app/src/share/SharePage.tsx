import { entryInfo, formatDateTime } from '@jabiz/client'
import { DescriptionList, EmailLinkForm, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { useDuration } from '../qb/view/PublicationContent'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { hrefOf, Link } from '../qb/router'
import { InAppBrowserNotice } from '../install'
import { readSharedPublication, sharePath } from './share'

/**
 * A publication's share link before signing in (Q15; docs/quizbuks/plans/Q15-share-link.md): what the quiz is, from
 * the public template, then the address only. The mailed link (platform decision D54) signs the taker in, or opens
 * their account, and leads back here, where the app's own page takes over. Signing in with a password stays offered.
 */
export function SharePage({ publicationId }: { publicationId: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const duration = useDuration()
  const shared = useQuery({ queryKey: ['qb', 'share', publicationId], queryFn: () => readSharedPublication(publicationId) })
  // Signing in by a mailed link is the entry's (QB_APP_EMAIL_LINK): off, the password only.
  const entry = useQuery({ queryKey: ['qb', 'entry'], queryFn: () => entryInfo(), staleTime: Infinity })
  const here = sharePath(publicationId)
  const signIn = `/login?next=${encodeURIComponent(here)}`

  if (shared.isPending || entry.isPending) return <PageState kind="loading" />
  if (shared.isError) return <PageState kind="error" description={tQb('failed')} />
  const p = shared.data
  if (!p) {
    return (
      <PageState kind="notFound" titleAs="h1" title={t('share.notFound')} description={t('share.notFoundHint')}
        action={<Link to="/login" className="text-primary text-sm underline" data-testid="share-sign-in">
          {t('share.home')}</Link>} />
    )
  }
  const survey = p.kind === 'SURVEY'
  return (
    <article className="flex flex-col gap-6" aria-labelledby="share-title" data-testid="share">
      {/* Shared in WeChat or LINE, the link opens inside it: the way out to the phone's browser first (Q50). */}
      <InAppBrowserNotice />
      <header className="flex flex-col gap-1">
        <h1 id="share-title" className="text-xl font-semibold">{p.name}</h1>
        {p.quizTitle !== p.name && <p className="text-muted-foreground text-sm">{p.quizTitle}</p>}
        {p.sponsorCompany && (
          <p className="text-muted-foreground text-sm" data-testid="share-sponsor">
            {t('share.by', { sponsor: p.sponsorCompany })}
          </p>
        )}
      </header>
      <DescriptionList data-testid="share-facts" items={[
        ...(survey ? [{ key: 'kind', label: t('detail.kind'), value: t('detail.survey') }] : []),
        { key: 'questions', label: t('detail.questions'), value: p.questionCount },
        { key: 'timeLimit', label: t('detail.timeLimit'), value: duration(p.timeLimitSec) },
        { key: 'maxReward', label: survey ? t('detail.surveyReward') : t('detail.maxReward'),
          value: formatKudos(p.maxReward) },
        { key: 'endAt', label: t('detail.endAt'), value: formatDateTime(p.endAt) },
      ]} />
      <section aria-labelledby="share-start" className="flex flex-col gap-3">
        {entry.data?.emailLink ? <>
          <h2 id="share-start" className="text-lg font-semibold">{t('share.start')}</h2>
          <p className="text-muted-foreground text-sm">{t('share.startHint')}</p>
          {/* The link's return path is a path of this site: the page under the app's base (/taker/p/<id>, Q30). */}
          <EmailLinkForm to={hrefOf(here)} submitLabel={t('share.start')} />
          <p className="text-muted-foreground text-sm">{t('share.delay')}</p>
        </> : <h2 id="share-start" className="text-lg font-semibold">{t('share.signIn')}</h2>}
        <Link to={signIn} className="text-primary self-center text-sm underline" data-testid="share-password">
          {t('share.password')}
        </Link>
      </section>
    </article>
  )
}
