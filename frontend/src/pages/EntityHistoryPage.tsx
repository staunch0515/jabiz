import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  DateTimePicker,
  DescriptionList,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  notify,
  PageState,
  Spinner,
  UI_NAMESPACE,
} from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Info, TriangleAlert } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams, useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
import HistoryTimeline from '../components/HistoryTimeline'
import OperationDrawer from '../components/OperationDrawer'
import AdminPage from '../layout/AdminPage'
import { differingFields } from '../meta/history'
import { useDataset, useDictionaries, useEntityMeta } from '../meta/hooks'
import { fieldLabel, formatValue } from '../meta/kinds'
import type { EntityInstance, HistoryVersion } from '../meta/types'
import { paths } from './paths'

/** Asks why an operation is reverted, then reverts it (POST /api/processes/executions/{seq}/revert). */
function RevertDialog({ seq, onClose, onReverted }: { seq: number | null; onClose(): void; onReverted(): Promise<void> }) {
  const { t } = useTranslation()
  const reasonId = useId()
  const [reason, setReason] = useState('')
  const [missing, setMissing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [shownSeq, setShownSeq] = useState(seq)
  // A new operation to revert starts with an empty reason.
  if (shownSeq !== seq) {
    setShownSeq(seq)
    setReason('')
    setMissing(false)
  }

  const submit = async () => {
    if (reason.trim() === '') {
      setMissing(true)
      return
    }
    setBusy(true)
    try {
      await unwrap(
        api.POST('/api/processes/executions/{processSeqId}/revert', {
          params: { path: { processSeqId: seq! } },
          body: { reason },
        }),
      )
      notify.success(t('history.reverted'))
      onClose()
      await onReverted()
    } catch (e) {
      notify.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={seq != null} onOpenChange={(open) => !open && !busy && onClose()}>
      <DialogContent>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault()
            void submit()
          }}
        >
          <DialogHeader>
            <DialogTitle>{t('history.revertTitle', { seq })}</DialogTitle>
            <DialogDescription className="sr-only">{t('history.revertReason')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={reasonId}>{t('history.revertReason')}</Label>
            <Input
              id={reasonId}
              value={reason}
              required
              aria-invalid={missing || undefined}
              aria-describedby={missing ? `${reasonId}-error` : undefined}
              onChange={(event) => {
                setReason(event.target.value)
                setMissing(false)
              }}
              data-testid="revert-reason"
            />
            {missing && (
              <p id={`${reasonId}-error`} className="text-destructive text-sm">
                {t('history.revertReasonRequired')}
              </p>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={onClose} disabled={busy}>
              {t('confirm.cancel', { ns: UI_NAMESPACE })}
            </Button>
            <Button type="submit" variant="destructive" id="revert-confirm" disabled={busy} aria-busy={busy || undefined}>
              {busy && <Spinner className="text-current" />}
              {t('history.revert')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * The history of one entry of a temporal entity (docs/design/04-temporal-append-only.md section 5): the timeline of
 * its versions, its state at any point in time (asOf, optionally as known at knownAt; both in the address) compared
 * with now, the operations that wrote it, and reverting an operation when the user holds temporal.revert.
 */
export default function EntityHistoryPage() {
  const { t, i18n } = useTranslation()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const { datasetId = '', entityId = '' } = useParams()
  const dataset = useDataset(datasetId)
  const entity = useEntityMeta(dataset.data?.entity)
  const dictionaries = useDictionaries(entity.data)
  const [searchParams, setSearchParams] = useSearchParams()
  const [operation, setOperation] = useState<number | null>(null)
  const [reverting, setReverting] = useState<number | null>(null)
  const asOfId = useId()
  const knownAtId = useId()

  const asOf = searchParams.get('asOf')
  const knownAt = searchParams.get('knownAt')

  const history = useQuery({
    queryKey: ['history', datasetId, entityId],
    queryFn: async () =>
      (await unwrap(
        api.GET('/api/datasets/{resourceId}/entities/{id}/history', {
          params: { path: { resourceId: datasetId, id: entityId } },
        }),
      )) as unknown as HistoryVersion[],
  })

  const read = (at: string | null, known: string | null) =>
    unwrap(
      api.GET('/api/datasets/{resourceId}/entities/{id}', {
        params: {
          path: { resourceId: datasetId, id: entityId },
          query: { asOf: at ?? undefined, knownAt: known ?? undefined },
        },
      }),
    ).catch((e) => {
      // Not existing at that time (or deleted by now) is an answer, not an error.
      if (e instanceof ApiError && e.status === 404) return null
      throw e
    }) as Promise<EntityInstance | null>

  const current = useQuery({ queryKey: ['entity', datasetId, entityId, null, null], queryFn: () => read(null, null) })
  const pointInTime = useQuery({
    queryKey: ['entity', datasetId, entityId, asOf, knownAt],
    enabled: !!(asOf || knownAt),
    queryFn: () => read(asOf, knownAt),
  })

  if (dataset.isLoading || entity.isLoading || history.isLoading) return <PageState kind="loading" />
  if (!dataset.data || !entity.data) return <PageState kind="notFound" titleAs="h1" title={datasetId} />
  if (history.error) {
    return (
      <PageState
        kind="error"
        titleAs="h1"
        title={history.error instanceof ApiError ? history.error.display : t('app.error')}
      />
    )
  }

  const setPoint = (at: string | null, known: string | null) => {
    const next = new URLSearchParams(searchParams)
    if (at) next.set('asOf', at)
    else next.delete('asOf')
    if (known) next.set('knownAt', known)
    else next.delete('knownAt')
    setSearchParams(next, { replace: true })
  }

  const then = pointInTime.data?.attributes as Record<string, unknown> | undefined
  const now = current.data?.attributes as Record<string, unknown> | undefined
  const differing = differingFields(then, now)
  const visible = entity.data.fields.filter((f) => !f.sensitive)
  const asking = !!(asOf || knownAt)
  const show = (field: (typeof visible)[number], values: Record<string, unknown> | undefined) =>
    formatValue(field, values?.[field.name], dictionaries, t, i18n.language)

  return (
    <AdminPage
      title={t('history.title', { entity: entity.data.label })}
      description={<code className="font-mono">{entityId}</code>}
      breadcrumb={[
        { label: t('nav.datasets'), to: '/data' },
        { label: entity.data.label, to: paths.dataset(datasetId) },
        { label: t('history.crumb') },
      ]}
      actions={
        <>
          {/* Values before and after each change (docs/design/21-audit-retention.md section 1). */}
          {can('audit.read') && dataset.data?.entity && (
            <Button variant="outline" size="sm" asChild>
              <Link to={paths.audit(dataset.data.entity, entityId)} data-testid="history-audit">
                {t('audit.open')}
              </Link>
            </Button>
          )}
          <Button variant="outline" size="sm" asChild>
            <Link to={paths.dataset(datasetId)}>{t('history.back')}</Link>
          </Button>
        </>
      }
    >
      <div className="grid items-start gap-4 lg:grid-cols-[13fr_11fr]">
        <Card>
          <CardContent>
            <HistoryTimeline
              entity={entity.data}
              versions={history.data ?? []}
              dictionaries={dictionaries}
              now={new Date()}
              canReadOperations={can('operation.read')}
              canRevert={can('temporal.revert') && !entity.data?.writeOnce}
              onViewAt={(version) => setPoint(version.effectStartTime, version.createdTime)}
              onOperation={setOperation}
              onRevert={setReverting}
            />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>
              <h2>{t('history.viewPoint')}</h2>
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="flex flex-wrap gap-4">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor={asOfId}>{t('history.pointInTime')}</Label>
                <DateTimePicker
                  id={asOfId}
                  value={asOf}
                  onChange={(value) => setPoint(value, knownAt)}
                  clearable
                  data-testid="point-as-of"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor={knownAtId}>{t('history.knownAt')}</Label>
                <DateTimePicker id={knownAtId} value={knownAt} onChange={(value) => setPoint(asOf, value)} clearable />
              </div>
            </div>
            {asking && pointInTime.isLoading && <Spinner />}
            {asking && pointInTime.error && (
              <Alert variant="destructive">
                <TriangleAlert aria-hidden />
                <AlertDescription>
                  {pointInTime.error instanceof ApiError ? pointInTime.error.display : String(pointInTime.error)}
                </AlertDescription>
              </Alert>
            )}
            {asking && pointInTime.isSuccess && !pointInTime.data && (
              <Alert role="status" data-testid="point-not-existing">
                <Info aria-hidden />
                <AlertDescription className="text-foreground">{t('history.notExisting')}</AlertDescription>
              </Alert>
            )}
            {asking && pointInTime.data && (
              <DescriptionList
                data-testid="point-in-time"
                items={[
                  { key: 'versionNo', label: t('fields.versionNo'), value: pointInTime.data.version },
                  ...visible.map((field) => ({
                    key: field.name,
                    label: fieldLabel(field, t),
                    value: (
                      <span className="flex flex-wrap items-center gap-2">
                        <span data-field={field.name}>{show(field, then)}</span>
                        {current.data && differing.has(field.name) && !field.systemManaged && (
                          <Badge variant="warning" className="whitespace-normal">
                            {t('history.differsFromNow')}: {show(field, now)}
                          </Badge>
                        )}
                      </span>
                    ),
                  })),
                ]}
              />
            )}
          </CardContent>
        </Card>
      </div>
      <OperationDrawer seq={operation} onClose={() => setOperation(null)} />
      <RevertDialog
        seq={reverting}
        onClose={() => setReverting(null)}
        onReverted={async () => {
          await queryClient.invalidateQueries({ queryKey: ['history', datasetId, entityId] })
          await queryClient.invalidateQueries({ queryKey: ['entity', datasetId, entityId] })
        }}
      />
    </AdminPage>
  )
}
