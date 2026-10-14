import { PageState } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'

/**
 * Home: the taker's feed of quizzes (U-10 – U-14) comes with the play pages (docs/quizbuks/plans/QP-pages.md, QP2);
 * until then it greets the taker (by the profile's name, else the sign-up name: an unverified taker has no profile).
 */
export function HomePage({ name }: { name: string }) {
  const { t } = useTranslation(APP)
  return (
    <section className="flex flex-col gap-4" aria-labelledby="home-title" data-testid="home">
      <h1 id="home-title" className="text-xl font-semibold">{t('home.greeting', { name })}</h1>
      <PageState kind="empty" title={t('home.title')} description={t('home.empty')} data-testid="home-empty" />
    </section>
  )
}
