import { formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Button,
  ConfirmDialog,
  DataTable,
  Input,
  Label,
  notify,
  PageHeader,
  PageState,
  type ColumnDef,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Search, UserMinus, UserPlus } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { addStaff, findStaff, readStaff, removeStaff, type StaffCandidate, type StaffMember } from './api'

export const STAFF_PATH = '/staff'
const STAFF_QUERY = ['qb', 'staff'] as const
/** A user id: 36 characters; the form takes a little more so a pasted one with spaces still fits. */
const ID_LENGTH = 64

/**
 * Naming a staff member (Q39): the ID the taker gave, looked up first so the sponsor sees whose it is, then added.
 * Every rule is the server's (a taker's ID, not on another sponsor's staff, not the sponsor).
 */
function AddStaff() {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [text, setText] = useState('')
  const [candidate, setCandidate] = useState<StaffCandidate | null>(null)
  const find = useMutation({
    mutationFn: (userId: string) => findStaff(userId),
    onMutate: () => setCandidate(null),
    onSuccess: setCandidate,
  })
  const add = useMutation({
    mutationFn: (found: StaffCandidate) => addStaff(found.userId),
    onSuccess: async (_output, found) => {
      notify.success(t('publishing.staff.added', { name: nameOf(found.displayName, t('publishing.staff.unnamed')) }))
      setCandidate(null)
      setText('')
      await queryClient.invalidateQueries({ queryKey: STAFF_QUERY })
    },
    onError: (error) => notify.error(problemText(error)),
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const userId = text.trim()
    if (userId) find.mutate(userId)
  }
  const name = candidate ? nameOf(candidate.displayName, t('publishing.staff.unnamed')) : ''
  return (
    <section className="flex flex-col gap-3 rounded-lg border p-4" aria-labelledby="staff-add-title"
      data-testid="staff-add">
      <h2 id="staff-add-title" className="text-lg font-semibold">{t('publishing.staff.addTitle')}</h2>
      <form onSubmit={submit} className="flex flex-col gap-2">
        <Label htmlFor={`${id}-user`}>{t('publishing.staff.idLabel')}</Label>
        <div className="flex flex-wrap gap-2">
          <Input id={`${id}-user`} value={text} maxLength={ID_LENGTH} autoComplete="off" spellCheck={false}
            aria-describedby={`${id}-hint`} className="max-w-md font-mono" data-testid="staff-id"
            onChange={(event) => {
              setText(event.target.value)
              setCandidate(null)
              find.reset()
            }} />
          <Button type="submit" variant="outline" disabled={!text.trim() || find.isPending}
            aria-busy={find.isPending || undefined} data-testid="staff-find">
            <Search aria-hidden />
            {t('publishing.staff.find')}
          </Button>
        </div>
        <p id={`${id}-hint`} className="text-muted-foreground text-sm">{t('publishing.staff.idHint')}</p>
      </form>
      {find.isError && (
        <Alert variant="destructive" data-testid="staff-find-failed">
          <AlertDescription>{problemText(find.error)}</AlertDescription>
        </Alert>
      )}
      {candidate && (
        <div className="flex flex-wrap items-center gap-3" role="status" data-testid="staff-candidate">
          <span>{t(candidate.onStaff ? 'publishing.staff.alreadyStaff' : 'publishing.staff.found', { name })}</span>
          {!candidate.onStaff && (
            <Button type="button" disabled={add.isPending} aria-busy={add.isPending || undefined}
              data-testid="staff-add-confirm" onClick={() => add.mutate(candidate)}>
              <UserPlus aria-hidden />
              {t('publishing.staff.add', { name })}
            </Button>
          )}
        </div>
      )}
    </section>
  )
}

function nameOf(displayName: string | null, unnamed: string): string {
  return displayName?.trim() || unnamed
}

/** The sponsor's staff, each removable. */
function StaffList() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const staff = useQuery({ queryKey: STAFF_QUERY, queryFn: readStaff })
  const remove = useMutation({
    mutationFn: (member: StaffMember) => removeStaff(member.userId),
    onSuccess: async (_output, member) => {
      notify.success(t('publishing.staff.removed', { name: nameOf(member.displayName, t('publishing.staff.unnamed')) }))
      await queryClient.invalidateQueries({ queryKey: STAFF_QUERY })
    },
    onError: (error) => notify.error(problemText(error)),
  })
  const columns = useMemo<ColumnDef<StaffMember, unknown>[]>(() => [
    { id: 'name', header: t('publishing.staff.name'), enableSorting: false,
      cell: ({ row }) => <span data-testid="staff-name">{nameOf(row.original.displayName, t('publishing.staff.unnamed'))}</span> },
    { id: 'id', header: t('publishing.staff.id'), enableSorting: false,
      cell: ({ row }) => <span className="font-mono text-xs break-all">{row.original.userId}</span> },
    { id: 'addedAt', header: t('publishing.staff.addedAt'), enableSorting: false,
      cell: ({ row }) => formatDateTime(row.original.addedAt) },
    { id: 'actions', header: () => <span className="sr-only">{t('publishing.staff.remove')}</span>,
      enableSorting: false,
      cell: ({ row }) => {
        const name = nameOf(row.original.displayName, t('publishing.staff.unnamed'))
        return (
          <ConfirmDialog title={t('publishing.staff.removeTitle', { name })}
            description={t('publishing.staff.removeText')} confirmLabel={t('publishing.staff.removeConfirm')}
            cancelLabel={t('publishing.staff.cancel')} destructive
            onConfirm={() => remove.mutateAsync(row.original).then(() => undefined)}
            trigger={<Button type="button" variant="ghost" size="sm" disabled={remove.isPending}
              data-testid="staff-remove"><UserMinus aria-hidden />{t('publishing.staff.remove')}</Button>} />
        )
      } },
  ], [t, remove])
  return (
    <section className="flex flex-col gap-3" aria-labelledby="staff-list-title" data-testid="staff-list">
      <h2 id="staff-list-title" className="text-lg font-semibold">{t('publishing.staff.listTitle')}</h2>
      {staff.isError ? (
        <PageState kind="error" description={problemText(staff.error)}
          action={<Button variant="outline" onClick={() => void staff.refetch()}>{tQb('retry')}</Button>} />
      ) : (
        <DataTable label={t('publishing.staff.listTitle')} columns={columns} data={staff.data ?? []}
          getRowId={(row) => row.userId} loading={staff.isPending} empty={t('publishing.staff.none')}
          rowProps={() => ({ 'data-testid': 'staff-row' })} />
      )}
    </section>
  )
}

/**
 * The staff page (docs/quizbuks/plans/Q39-sponsor-staff.md): the people who hand out the sponsor's prizes at the
 * counter with the app on their own phone. Only the sponsor names and removes them.
 */
export function StaffPage() {
  const { t } = useTranslation(SPONSOR)
  return (
    <section className="flex flex-col gap-6" data-testid="staff-page">
      <PageHeader title={t('publishing.staff.title')} description={t('publishing.staff.intro')} />
      <AddStaff />
      <StaffList />
    </section>
  )
}
