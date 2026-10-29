import { formatDateTime } from '@jabiz/client'
import { Alert, AlertDescription, Button, ConfirmDialog, notify } from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRightLeft, Layers, Undo2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { readVersions } from '../content/api'
import { quizPath, versionsQuery } from '../content/paths'
import { SPONSOR } from '../messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { actionsOf, isStale, switchVersion, withdrawSwitch, type Publication } from './api'
import { PUBLICATIONS_QUERY } from './paths'

/** Why a switch that was approved did not happen (`PublicationVersions.result`), by its code. */
const DROPPED: Record<string, string> = {
  QB_PUBLICATION_SWITCH_ENDED: 'ended',
  QB_PUBLICATION_SWITCH_TAKEN_DOWN: 'takenDown',
  QB_USER_BANNED: 'banned',
  QB_PUBLICATION_QUIZ_REMOVED: 'quizRemoved',
}

/**
 * Which version of its quiz a publication shows (Q34b) and switching it to another (Q51). Takers see that version's
 * title, introduction, materials and questions; an attempt keeps the version it started with. When the quiz has a newer
 * version with the same full score, a publication that runs may switch to it: the switch is reviewed while takers keep
 * seeing the current version, one switch at a time, and may be withdrawn while in review.
 */
export function VersionNotice({ publication, disabled, onStale }: {
  publication: Publication
  disabled?: boolean
  onStale: () => void
}) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const { publicationId, quizId, versionId, versionLabel, fullScore, revision } = publication
  const versions = useQuery({ queryKey: versionsQuery(quizId ?? ''), queryFn: () => readVersions(quizId!),
    enabled: !!quizId })
  if (!quizId || !versionId || !versionLabel) return null
  const mine = versions.data?.find((v) => v.versionId === versionId)
  const newest = versions.data?.reduce<typeof mine>((top, v) => (!top || v.versionNo > top.versionNo ? v : top), undefined)
  const newer = mine && newest && newest.versionNo > mine.versionNo ? newest : undefined
  const canSwitch = actionsOf(publication.status, publication.pausedBy, publication.endReason).switchVersion
  const pending = publication.pendingVersionLabel
  const dropped = publication.switchRejectCode ? DROPPED[publication.switchRejectCode] : undefined

  /** Runs a change; afterwards the lists and this page read again. A failure keeps the confirmation open. */
  const change = (run: () => Promise<unknown>, done: string) => run().then(
    async () => {
      notify.success(done)
      await queryClient.invalidateQueries({ queryKey: PUBLICATIONS_QUERY })
    },
    (error: unknown) => {
      if (isStale(error)) {
        onStale()
        return
      }
      notify.error(problemText(error))
      throw error
    },
  )

  const openQuiz = (
    <Link to={quizPath(quizId)} className="underline underline-offset-4">{t('publishing.version.openQuiz')}</Link>
  )
  let offer = null
  if (pending) {
    offer = (
      <span className="flex flex-wrap items-center gap-2" data-testid="publication-switch-pending">
        <span>{t('publishing.version.pending', { label: pending, current: versionLabel,
          time: publication.pendingSince ? formatDateTime(publication.pendingSince) : '' })}</span>
        <ConfirmDialog title={t('publishing.version.withdrawTitle', { label: pending })}
          description={t('publishing.version.withdrawText', { current: versionLabel })}
          confirmLabel={t('publishing.version.withdraw')} cancelLabel={t('common.cancel')}
          onConfirm={() => change(() => withdrawSwitch(publicationId, revision), t('publishing.version.withdrawn'))}
          trigger={
            <Button size="sm" variant="outline" disabled={disabled} data-testid="publication-switch-withdraw">
              <Undo2 aria-hidden />
              {t('publishing.version.withdraw')}
            </Button>
          } />
      </span>
    )
  } else if (newer && canSwitch && newer.fullScore === fullScore) {
    offer = (
      <span className="flex flex-wrap items-center gap-2" data-testid="publication-version-newer">
        <span>{t('publishing.version.newerSwitch', { label: newer.label, current: versionLabel })}</span>
        <ConfirmDialog title={t('publishing.version.switchTitle', { label: newer.label })}
          description={t('publishing.version.switchText', { label: newer.label, current: versionLabel })}
          confirmLabel={t('publishing.version.switch', { label: newer.label })} cancelLabel={t('common.cancel')}
          onConfirm={() => change(() => switchVersion(publicationId, newer.versionId, revision),
            t('publishing.version.switchSent', { label: newer.label }))}
          trigger={
            <Button size="sm" disabled={disabled} data-testid="publication-switch">
              <ArrowRightLeft aria-hidden />
              {t('publishing.version.switch', { label: newer.label })}
            </Button>
          } />
      </span>
    )
  } else if (newer) {
    // Another full score would leave the tiers wrong (D-Q51-2); a publication that does not run cannot switch.
    offer = (
      <span data-testid="publication-version-newer">
        {canSwitch
          ? t('publishing.version.otherScore', { label: newer.label, newFullScore: newer.fullScore, fullScore })
          : t('publishing.version.newer', { label: newer.label })}{' '}
        {openQuiz}
      </span>
    )
  }
  return (
    <Alert variant={newer && !pending ? 'warning' : 'default'} data-testid="publication-version"
      data-newer={!!newer || undefined}>
      <Layers aria-hidden />
      <AlertDescription className="text-current flex flex-col gap-2">
        <span>{t('publishing.version.uses', { label: versionLabel })}</span>
        {offer}
        {!pending && (publication.switchRejectReason || publication.switchRejectCode) && (
          <span data-testid="publication-switch-rejected">
            {publication.switchRejectReason
              ? t('publishing.version.rejected', { reason: publication.switchRejectReason })
              : t('publishing.version.dropped', { why: dropped
                ? t(`publishing.version.droppedBecause.${dropped}`)
                : publication.switchRejectCode })}
          </span>
        )}
      </AlertDescription>
    </Alert>
  )
}
