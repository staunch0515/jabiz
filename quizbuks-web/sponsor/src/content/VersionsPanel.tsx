import { formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  ConfirmDialog,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  MarkdownView,
  notify,
  PageState,
  type ColumnDef,
} from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Copy, Eye } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { navigate } from '../qb/router'
import { cloneQuiz, readVersion, readVersions, type VersionRow } from './api'
import { ContentImage } from './ContentImage'
import { MaterialContent, useKindLabel } from './MaterialsPanel'
import { quizPath, QUIZZES_QUERY, versionsQuery } from './paths'

/** One version's whole content, read only, as it was saved (correct options marked). */
function VersionView({ versionId }: { versionId: string }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const kindLabel = useKindLabel()
  const version = useQuery({ queryKey: [...QUIZZES_QUERY, 'version', versionId], queryFn: () => readVersion(versionId),
    staleTime: Infinity })
  if (version.isPending) return <PageState kind="loading" />
  if (version.isError || !version.data) return <PageState kind="error" description={tQb('failed')} />
  const { content } = version.data
  return (
    <div className="flex flex-col gap-5" data-testid="version-content">
      <div className="flex flex-col gap-2">
        <h3 className="text-lg font-semibold" data-testid="version-title">{content.title}</h3>
        <p className="text-muted-foreground text-sm">
          {content.timeLimitSec == null ? t('versions.noTimeLimit')
            : t('versions.timeLimit', { seconds: content.timeLimitSec.toLocaleString() })}
        </p>
        {content.cover && <ContentImage fileId={content.cover} width={160} />}
        {content.intro && <MarkdownView text={content.intro} />}
      </div>
      {content.materials.length > 0 && (
        <section className="flex flex-col gap-3" aria-labelledby={`${versionId}-materials`}>
          <h4 id={`${versionId}-materials`} className="font-medium">{t('editor.tabs.materials')}</h4>
          <ol className="flex flex-col gap-3">
            {content.materials.map((material) => (
              <li key={material.no} className="flex flex-col gap-2 rounded-md border p-3">
                <p className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">
                    {material.title ? t('materials.numberTitled', { number: material.no, title: material.title })
                      : t('materials.number', { number: material.no })}
                  </span>
                  <Badge variant="outline">{kindLabel(material.kind)}</Badge>
                </p>
                {material.description && <p className="text-muted-foreground text-sm">{material.description}</p>}
                <MaterialContent material={material} />
              </li>
            ))}
          </ol>
        </section>
      )}
      <section className="flex flex-col gap-3" aria-labelledby={`${versionId}-questions`}>
        <h4 id={`${versionId}-questions`} className="font-medium">{t('editor.tabs.questions')}</h4>
        <ol className="flex flex-col gap-3">
          {content.questions.map((question) => (
            <li key={question.no} className="flex flex-col gap-2 rounded-md border p-3" data-testid="version-question">
              <p className="flex flex-wrap items-center justify-between gap-2">
                <span className="font-medium">{t('questions.number', { number: question.no })}</span>
                <Badge variant="outline">{t('questions.pointsValue', { points: question.points })}</Badge>
              </p>
              <p className="whitespace-pre-wrap">{question.stem}</p>
              {question.image && <ContentImage fileId={question.image} width={160} />}
              <ol className="flex flex-col gap-1 text-sm">
                {question.options.map((option) => (
                  <li key={option.no} className="flex items-center gap-2" data-correct={option.correct}>
                    {option.correct
                      ? <Check aria-hidden className="text-success size-4 shrink-0" />
                      : <span aria-hidden className="size-4 shrink-0" />}
                    <span>{option.no}.</span>
                    <span>{option.text ?? t('questions.imageOnly')}</span>
                    {option.correct && <span className="sr-only">{t('questions.correctMark')}</span>}
                    {option.image && <ContentImage fileId={option.image} width={64} />}
                  </li>
                ))}
              </ol>
            </li>
          ))}
        </ol>
      </section>
    </div>
  )
}

/**
 * The quiz's versions (M-27), newest first: each saved once and never changed by later edits. A version can be
 * viewed, or copied into a new draft (M-28, "restore": the quiz itself keeps its draft).
 */
export function VersionsPanel({ quizId, stale }: { quizId: string; stale: boolean }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const versions = useQuery({ queryKey: versionsQuery(quizId), queryFn: () => readVersions(quizId) })
  const [viewing, setViewing] = useState<VersionRow | null>(null)

  const columns = useMemo<ColumnDef<VersionRow, unknown>[]>(() => [
    { id: 'label', header: t('versions.label'), cell: ({ row }) => <span className="font-medium">{row.original.label}</span> },
    { id: 'title', header: t('versions.title'), cell: ({ row }) => row.original.title },
    { id: 'questionCount', header: t('quizzes.questions'), meta: { className: 'text-right' },
      cell: ({ row }) => row.original.questionCount },
    { id: 'fullScore', header: t('versions.fullScore'), meta: { className: 'text-right' },
      cell: ({ row }) => row.original.fullScore },
    { id: 'versionedAt', header: t('versions.versionedAt'), cell: ({ row }) => formatDateTime(row.original.versionedAt) },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('versions.actions')}</span>,
      cell: ({ row }) => (
        <span className="flex flex-wrap justify-end gap-2">
          <Button variant="outline" size="sm" data-testid="version-view"
            aria-label={t('versions.view', { label: row.original.label })} onClick={() => setViewing(row.original)}>
            <Eye aria-hidden />
            {t('versions.viewShort')}
          </Button>
          <ConfirmDialog title={t('versions.copyTitle', { label: row.original.label })}
            description={t('versions.copyText')} confirmLabel={t('versions.copy')} cancelLabel={t('common.cancel')}
            onConfirm={() => cloneQuiz(quizId, row.original.versionNo).then(
              async (output) => {
                notify.success(t('versions.copied', { label: row.original.label }))
                await queryClient.invalidateQueries({ queryKey: QUIZZES_QUERY })
                navigate(quizPath(output.quizId))
              },
              (error: unknown) => {
                notify.error(problemText(error))
                throw error
              },
            )}
            trigger={
              <Button variant="outline" size="sm" disabled={stale} data-testid="version-copy"
                aria-label={t('versions.copyNamed', { label: row.original.label })}>
                <Copy aria-hidden />
                {t('versions.copy')}
              </Button>
            } />
        </span>
      ),
    },
  ], [t, quizId, queryClient, stale])

  if (versions.isError) {
    return <PageState kind="error" description={tQb('failed')}
      action={<Button variant="outline" onClick={() => void versions.refetch()}>{tQb('retry')}</Button>} />
  }
  return (
    <div className="flex flex-col gap-4" data-testid="versions-panel">
      <p className="text-muted-foreground text-sm">{t('versions.hint')}</p>
      <DataTable label={t('versions.table')} columns={columns} data={versions.data ?? []}
        getRowId={(row) => row.versionId} loading={versions.isPending} empty={t('versions.none')}
        rowProps={(row) => ({ 'data-testid': 'version-row', 'data-label': row.label })} />
      <Dialog open={viewing !== null} onOpenChange={(open) => {
        if (!open) setViewing(null)
      }}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-3xl" data-testid="version-dialog">
          <DialogHeader>
            <DialogTitle>{viewing ? t('versions.viewTitle', { label: viewing.label }) : ''}</DialogTitle>
            <DialogDescription>
              {viewing ? t('versions.viewHint', { time: formatDateTime(viewing.versionedAt) }) : ''}
            </DialogDescription>
          </DialogHeader>
          {viewing && <VersionView versionId={viewing.versionId} />}
        </DialogContent>
      </Dialog>
    </div>
  )
}
