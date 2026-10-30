import { useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  PageHeader,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { LoadFailed } from './components'
import { useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { OperatorFields } from './operator-components'
import { OPERATORS_KEY, readOperators, saveOperator, type Operator } from './operator-api'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { name: 'name', status: 'status', createdAt: 'createdAt' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }

/** Creates an operator (`QB_OPERATOR_SAVE`); its page opens next, to name its members. */
function NewDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [values, setValues] = useState({ name: '', contactName: '', contactEmail: '', note: '' })
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const complete = !!values.name.trim() && !!values.contactName.trim() && !!values.contactEmail.trim()
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || !complete) return
    setBusy(true)
    setProblem(null)
    try {
      const saved = await saveOperator({ name: values.name.trim(), contactName: values.contactName.trim(),
        contactEmail: values.contactEmail.trim(), note: values.note.trim() || null })
      void queryClient.invalidateQueries({ queryKey: OPERATORS_KEY })
      navigate(PATH.operator(saved.operatorId))
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid="operator-new-dialog">
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('operator.list.newTitle')}</DialogTitle>
            <DialogDescription>{t('operator.list.newHint')}</DialogDescription>
          </DialogHeader>
          <OperatorFields id={id} {...values}
            onChange={(field, value) => setValues((current) => ({ ...current, [field]: value }))} />
          {problem && <Alert variant="destructive" data-testid="operator-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('operator.cancel')}</Button>
            <Button type="submit" disabled={busy || !complete} aria-busy={busy || undefined}
              data-testid="operator-create">{t('operator.list.create')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * Headquarters' operators (docs/quizbuks/plans/Q48-operators.md, Q48a): the agents who run sponsors in the back office
 * at /operator/, newest first, with their members and sponsors. A super administrator creates one; its page names the
 * members and moves sponsors to it.
 */
export default function OperatorsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const status = useLabels(DICTIONARY.operatorStatus)
  const [creating, setCreating] = useState(false)
  const { query, table, sortable } = usePaged([...OPERATORS_KEY, 'list'], readOperators, true, SORTS)
  const columns = useMemo<ColumnDef<Operator, unknown>[]>(() => [
    {
      id: 'name',
      header: t('operator.fields.name'),
      cell: ({ row }) => (
        <Link to={PATH.operator(row.original.operatorId)} className={linkClass} data-testid="operator-row-link">
          {row.original.name}
        </Link>
      ),
    },
    { id: 'contact', header: t('operator.fields.contactName'),
      cell: ({ row }) => `${row.original.contactName} · ${row.original.contactEmail}` },
    { id: 'status', header: t('operator.fields.status'), cell: ({ row }) => (
      <Badge variant={row.original.status === 'ACTIVE' ? 'secondary' : 'outline'}>{status(row.original.status)}</Badge>
    ) },
    { id: 'members', header: t('operator.list.members'), meta: right, cell: ({ row }) => row.original.members },
    { id: 'sponsors', header: t('operator.list.sponsors'), meta: right, cell: ({ row }) => row.original.sponsors },
    { id: 'createdAt', header: t('operator.list.createdAt'), cell: ({ row }) => when(row.original.createdAt) },
  ], [t, status])
  return (
    <div className="flex flex-col gap-4" data-testid="operators">
      <PageHeader title={t('operator.list.title')} description={t('operator.list.description')} className="pb-0"
        actions={can(PERMISSION.operatorsWrite) && (
          <Button onClick={() => setCreating(true)} data-testid="operator-new">{t('operator.list.new')}</Button>
        )} />
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('operator.list.title')} columns={sortable(columns)} {...table} getRowId={(row) => row.operatorId}
            empty={t('operator.list.none')}
            rowProps={(row) => ({ 'data-testid': 'operator-row', 'data-id': row.operatorId })} />
        )}
      {creating && <NewDialog onClose={() => setCreating(false)} />}
    </div>
  )
}
