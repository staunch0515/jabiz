import { Alert, AlertDescription } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { Layers } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { readVersions } from '../content/api'
import { quizPath, versionsQuery } from '../content/paths'
import { SPONSOR } from '../messages'
import { Link } from '../qb/router'

/**
 * Which version of its quiz a publication shows (Q34b): takers see that version's title, introduction, materials and
 * questions, fixed while it runs. When the quiz has a newer version, the sponsor is told that this publication does not
 * show it, so a material added later is not looked for here in vain.
 */
export function VersionNotice({ quizId, versionId, versionLabel }: { quizId: string | null; versionId: string | null;
  versionLabel: string | null }) {
  const { t } = useTranslation(SPONSOR)
  const versions = useQuery({ queryKey: versionsQuery(quizId ?? ''), queryFn: () => readVersions(quizId!),
    enabled: !!quizId })
  if (!quizId || !versionId || !versionLabel) return null
  const mine = versions.data?.find((v) => v.versionId === versionId)
  const newest = versions.data?.reduce<typeof mine>((top, v) => (!top || v.versionNo > top.versionNo ? v : top), undefined)
  const newer = !!mine && !!newest && newest.versionNo > mine.versionNo
  return (
    <Alert variant={newer ? 'warning' : 'default'} data-testid="publication-version" data-newer={newer || undefined}>
      <Layers aria-hidden />
      <AlertDescription className="text-current">
        <span>{t('publishing.version.uses', { label: versionLabel })}</span>
        {newer && (
          <span data-testid="publication-version-newer">
            {t('publishing.version.newer', { label: newest!.label })}{' '}
            <Link to={quizPath(quizId)} className="underline underline-offset-4">{t('publishing.version.openQuiz')}</Link>
          </span>
        )}
      </AlertDescription>
    </Alert>
  )
}
