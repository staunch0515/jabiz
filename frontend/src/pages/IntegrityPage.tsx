import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  DataTable,
  DescriptionList,
  notify,
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  Spinner,
  type ColumnDef,
} from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck, Info, TriangleAlert } from 'lucide-react'
import { useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import type { IntegrityCheckDetail, IntegrityCheckSummary, IntegrityProblem, VerifyOutput } from '../api/integrity'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
import UserName from '../components/UserName'
import { useLocalPage } from '../components/useLocalPage'
import AdminPage from '../layout/AdminPage'
import { runProcess } from '../lib/calls'
import { formatDateTime } from '../meta/format'

const KIND_VARIANTS: Record<string, 'destructive' | 'warning'> = {
  MODIFIED: 'destructive', MISSING: 'destructive', CHAIN_BROKEN: 'destructive', SEAL_ALTERED: 'destructive',
  UNPROTECTED: 'warning', OTHER_KEY: 'warning',
}

const fail = (e: unknown) => notify.error(e instanceof ApiError ? e.display : String(e))

/** The problems of one verification. */
export function ProblemTable({ problems }: { problems: IntegrityProblem[] }) {
  const { t } = useTranslation()
  const page = useLocalPage(problems, 50)
  const columns = useMemo<ColumnDef<IntegrityProblem, unknown>[]>(
    () => [
      {
        id: 'kind',
        header: t('integrity.kind'),
        cell: ({ row }) => (
          <Badge variant={KIND_VARIANTS[row.original.kind ?? ''] ?? 'secondary'}>
            {t(`integrity.kinds.${row.original.kind}`)}
          </Badge>
        ),
      },
      { id: 'seal', header: t('integrity.seal'), cell: ({ row }) => row.original.sealNo },
      { id: 'table', header: t('integrity.table'), cell: ({ row }) => row.original.table },
      { id: 'row', header: t('integrity.row'), cell: ({ row }) => row.original.key },
      {
        id: 'detail',
        header: t('integrity.detail'),
        meta: { className: 'whitespace-normal' },
        cell: ({ row }) => row.original.detail,
      },
    ],
    [t],
  )
  return (
    <div data-testid="integrity-problems">
      <DataTable
        label={t('integrity.kind')}
        columns={columns}
        getRowId={(p, index) => `${p.kind}-${p.sealNo}-${p.table}-${p.key}-${index}`}
        {...page.paging}
        data={page.data}
      />
    </div>
  )
}

/** A titled card of the page. */
function Part({ title, children, testId }: { title: ReactNode; children: ReactNode; testId?: string }) {
  return (
    <Card data-testid={testId}>
      <CardHeader>
        <CardTitle>
          <h2 className="text-base font-semibold">{title}</h2>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">{children}</CardContent>
    </Card>
  )
}

/**
 * The integrity seals (docs/design/21-audit-retention.md section 2.4): the head of the chain - its hash is worth
 * keeping outside the system -, verifying the seals (integrity.verify) and the verifications with what they found.
 */
export default function IntegrityPage() {
  const { t } = useTranslation()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const [running, setRunning] = useState(false)
  // The verification in the details sheet; kept while the sheet slides out, so focus returns to its button.
  const [detail, setDetail] = useState<{ check: IntegrityCheckDetail; open: boolean } | null>(null)

  const head = useQuery({
    queryKey: ['integrity', 'head'],
    queryFn: async () => unwrap(api.GET('/api/integrity/head')),
  })
  const checks = useQuery({
    queryKey: ['integrity', 'checks'],
    queryFn: async () => unwrap(api.GET('/api/integrity/checks', { params: { query: { limit: 50 } } })),
  })
  const checkPage = useLocalPage(checks.data?.items, 20)

  const columns = useMemo<ColumnDef<IntegrityCheckSummary, unknown>[]>(() => {
    const open = async (checkNo: number) => {
      try {
        const check = await unwrap(api.GET('/api/integrity/checks/{checkNo}', { params: { path: { checkNo } } }))
        setDetail({ check, open: true })
      } catch (e) {
        fail(e)
      }
    }
    const right = { className: 'text-right tabular-nums' }
    return [
      {
        id: 'checked',
        header: t('integrity.checked'),
        cell: ({ row: { original: check } }) => (
          <span className="flex flex-col">
            <span>{formatDateTime(check.checkedTime)}</span>
            <span className="text-muted-foreground text-xs">
              <UserName id={check.actorId} />
            </span>
          </span>
        ),
      },
      {
        id: 'outcome',
        header: t('integrity.outcome'),
        cell: ({ row: { original: check } }) => (
          <Badge variant={check.intact ? 'success' : 'destructive'} data-testid={`integrity-outcome-${check.checkNo}`}>
            {check.intact ? t('integrity.intact') : t('integrity.problems', { count: check.problemCount })}
          </Badge>
        ),
      },
      {
        id: 'seals',
        header: t('integrity.seals'),
        cell: ({ row: { original: check } }) => (check.sealCount ? `${check.fromSeal} – ${check.toSeal}` : '–'),
      },
      { id: 'rows', header: t('integrity.rows'), meta: right, cell: ({ row }) => row.original.rowCount },
      { id: 'unsealed', header: t('integrity.unsealed'), meta: right, cell: ({ row }) => row.original.unsealedCount },
      {
        id: 'open',
        header: () => <span className="sr-only">{t('list.actions')}</span>,
        meta: { pinned: 'right' },
        cell: ({ row: { original: check } }) => (
          <Button variant="outline" size="sm" onClick={() => void open(check.checkNo!)}
            data-testid={`integrity-open-${check.checkNo}`}>
            {t('integrity.details')}
          </Button>
        ),
      },
    ]
  }, [t])

  const verify = async () => {
    setRunning(true)
    try {
      const output = await runProcess<VerifyOutput>('INTEGRITY_VERIFY', {})
      if (output.intact) notify.success(t('integrity.intact'))
      else notify.error(t('integrity.problems', { count: output.problemCount }))
      await queryClient.invalidateQueries({ queryKey: ['integrity'] })
      const check = await unwrap(
        api.GET('/api/integrity/checks/{checkNo}', { params: { path: { checkNo: output.checkNo } } }),
      )
      setDetail({ check, open: true })
    } catch (e) {
      fail(e)
    } finally {
      setRunning(false)
    }
  }

  const current = head.data
  const shown = detail?.check
  return (
    <AdminPage
      title={t('integrity.title')}
      actions={
        can('integrity.verify') && (
          <Button disabled={running} onClick={() => void verify()} data-testid="integrity-verify">
            {running && <Spinner className="text-current" />}
            {t('integrity.verify')}
          </Button>
        )
      }
    >
      <Part title={t('integrity.head')} testId="integrity-head">
        {head.isLoading ? (
          <Spinner />
        ) : current?.sealNo == null ? (
          <p className="text-muted-foreground text-sm">{t('integrity.noSeals')}</p>
        ) : (
          <DescriptionList
            columns={2}
            items={[
              { key: 'seal', label: t('integrity.seal'), value: current.sealNo },
              { key: 'sealed', label: t('integrity.sealedTime'), value: formatDateTime(current.sealedTime) },
              { key: 'rows', label: t('integrity.rows'), value: current.rowCount },
              { key: 'key', label: t('integrity.keyId'), value: current.keyId },
              {
                key: 'hash',
                label: t('integrity.hash'),
                value: (
                  <code className="font-mono text-xs" data-testid="integrity-head-hash">
                    {current.sealHash}
                  </code>
                ),
                copy: current.sealHash,
              },
            ]}
          />
        )}
        {current?.keyId && current.keyId !== current.currentKeyId && (
          <Alert variant="warning">
            <TriangleAlert aria-hidden />
            <AlertDescription>{t('integrity.otherKey')}</AlertDescription>
          </Alert>
        )}
      </Part>
      <Part title={t('integrity.checks')}>
        <div data-testid="integrity-checks">
          <DataTable
            label={t('integrity.checks')}
            columns={columns}
            getRowId={(check) => String(check.checkNo)}
            loading={checks.isLoading}
            {...checkPage.paging}
            data={checkPage.data}
          />
        </div>
      </Part>
      <Sheet open={detail?.open ?? false} onOpenChange={(open) => !open && setDetail((d) => d && { ...d, open: false })}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-4xl" data-testid="integrity-check-detail">
          <SheetHeader>
            <SheetTitle>{shown ? t('integrity.checkTitle', { no: shown.check?.checkNo }) : ''}</SheetTitle>
            <SheetDescription className="sr-only">{t('integrity.details')}</SheetDescription>
          </SheetHeader>
          {shown && (
            <div className="flex flex-col gap-4 px-4 pb-6">
              {shown.problems?.length ? (
                <ProblemTable problems={shown.problems} />
              ) : (
                <Alert variant="success">
                  <CircleCheck aria-hidden />
                  <AlertDescription>{t('integrity.intact')}</AlertDescription>
                </Alert>
              )}
              {(shown.check?.problemCount ?? 0) > (shown.problems?.length ?? 0) && (
                <Alert>
                  <Info aria-hidden />
                  <AlertDescription>{t('integrity.more', { count: shown.check?.problemCount })}</AlertDescription>
                </Alert>
              )}
            </div>
          )}
        </SheetContent>
      </Sheet>
    </AdminPage>
  )
}
