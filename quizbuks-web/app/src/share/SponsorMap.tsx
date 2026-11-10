import type { ReactNode } from 'react'
import { PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { PersonalMap } from '../maps/MapsPage'
import { PROFILE_QUERY } from '../pages/ProfileForm'
import { readMyProfile } from '../qb/api'
import { QB } from '../qb/messages'
import { readPageMaps, readPublicPageMap } from '../qb/tree/api'
import { KnowledgeMap } from '../qb/tree/KnowledgeMap'

/**
 * A Sponsor's knowledge map on their page (F-S3c, docs/quizbuks/plans/impl/F-S3.md §2, §4): signed in, as the Taker
 * sees it (`qb.app.trees` by the handle, then `qb.app.tree` and the feed's `tree`); signed out, the names and
 * conditions only (`qb.public.sponsor-page-tree`). Nothing when the Sponsor has no map in effect.
 */
export function SponsorMap({ handle, signedIn }: { handle: string; signedIn: boolean }) {
  return signedIn ? <SignedInMap handle={handle} /> : <PublicMap handle={handle} />
}

function Section({ children }: { children: ReactNode }) {
  const { t } = useTranslation(APP)
  return (
    <section aria-labelledby="sponsor-page-map" className="flex flex-col gap-3" data-testid="sponsor-page-map">
      <h2 id="sponsor-page-map" className="text-lg font-semibold">{t('sponsorPage.map')}</h2>
      {children}
    </section>
  )
}

function SignedInMap({ handle }: { handle: string }) {
  const { t: tQb } = useTranslation(QB)
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  const maps = useQuery({ queryKey: ['qb', 'sponsor-page-maps', handle], queryFn: () => readPageMaps(handle) })
  if (maps.isPending || profile.isPending) return null
  if (maps.isError) return <Section><PageState kind="error" description={tQb('failed')} /></Section>
  const map = maps.data[0]
  if (!map) return null
  return (
    <Section>
      <PersonalMap treeId={map.treeId} country={profile.data?.countryCode ?? null} heading="h3"
        missing={null} failed={<PageState kind="error" description={tQb('failed')} />} />
    </Section>
  )
}

function PublicMap({ handle }: { handle: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const map = useQuery({ queryKey: ['qb', 'sponsor-page-public-map', handle], queryFn: () => readPublicPageMap(handle) })
  if (map.isPending) return null
  if (map.isError) return <Section><PageState kind="error" description={tQb('failed')} /></Section>
  if (!map.data) return null
  return (
    <Section>
      <p className="text-muted-foreground text-sm" data-testid="sponsor-page-map-sign-in">{t('sponsorPage.mapSignIn')}</p>
      <KnowledgeMap graph={map.data} />
    </Section>
  )
}
