import {
  AUTH_NAMESPACE,
  Badge,
  Button,
  Combobox,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  effectiveMinutes,
  Label,
  notify,
  SessionDeviceIcon,
  sessionDeviceName,
  Textarea,
  type ColumnDef,
} from '@jabiz/ui'
import { formatDateTime, type SessionInfo } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { useAuth } from '../auth/AuthContext'
import { runProcess } from '../lib/calls'
import AdminPage from '../layout/AdminPage'

/** The users, searched by user name through their default dataset (`security.user.read`). */
const USER_DATASET = 'urn:jabiz:dataset:platform:SecUser'
/** How long typing must pause before the server is asked. */
const DEBOUNCE_MS = 250
const REVOKE_PROCESS = 'SEC_USER_SESSIONS_REVOKE'
const REVOKE_PERMISSION = 'security.user.session.revoke'

/** Which sessions the dialog ends: one, or (null) every one of the user's. */
type Target = { session: SessionInfo | null }

/** A LIKE pattern matching user names that contain the text (the server takes the pattern as it is). */
function containing(text: string): string {
  return `%${text.replace(/[\\%_]/g, (c) => `\\${c}`)}%`
}

/**
 * Picks a user by searching their user names. Users declare no display field (02 section 6.3), so the reference
 * picker's lookup does not serve them; the users' dataset query does, with its filter on the user name.
 */
function UserPicker({ id, value, onChange }: { id: string; value: string | null; onChange: (id: string | null) => void }) {
  const { t } = useTranslation()
  const [typed, setTyped] = useState('')
  const [q, setQ] = useState('')
  const [label, setLabel] = useState<string | undefined>(undefined)
  useEffect(() => {
    const timer = setTimeout(() => setQ(typed.trim()), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [typed])
  const users = useQuery({
    queryKey: ['security', 'session-users', q],
    queryFn: async () => {
      const result = await unwrap(
        api.POST('/api/datasets/{resourceId}/query', {
          params: { path: { resourceId: USER_DATASET } },
          body: {
            filters: q ? [{ field: 'userName', op: 'LIKE', value: containing(q) }] : [],
            sorts: [{ field: 'userName', asc: true }],
            limit: 20,
          },
        }),
      )
      return (result.items ?? []).map((item) => {
        const attributes = (item.attributes ?? {}) as Record<string, unknown>
        const userName = String(attributes.userName ?? item.id)
        const displayName = attributes.displayName ? String(attributes.displayName) : undefined
        return { value: String(item.id), label: userName, description: displayName !== userName ? displayName : undefined }
      })
    },
    staleTime: 30_000,
  })
  const options = users.data ?? []
  return (
    <Combobox
      id={id}
      value={value}
      onChange={(next) => {
        setLabel(options.find((option) => option.value === next)?.label)
        onChange(next)
      }}
      options={options}
      onSearch={setTyped}
      loading={users.isFetching}
      selectedLabel={label}
      placeholder={t('sessions.pickUser')}
      clearable
      data-testid="session-user-select"
    />
  )
}

/**
 * A user's live sessions at every sign-in entry, for administrators (docs/design/10-security.md section 15.9; phase
 * 16g): `GET /api/security/sessions` (`security.user.session.read`). Ending one or all runs SEC_USER_SESSIONS_REVOKE
 * with a reason (`security.user.session.revoke`, a recent second factor: the step-up dialog asks for it).
 */
export default function SessionsPage() {
  const { t } = useTranslation()
  const { t: tAuth } = useTranslation(AUTH_NAMESPACE)
  const { can } = useAuth()
  const [userId, setUserId] = useState<string | null>(null)
  const [target, setTarget] = useState<Target | null>(null)
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const userField = useId()
  const reasonField = useId()
  const mayRevoke = can(REVOKE_PERMISSION)

  const sessions = useQuery({
    queryKey: ['security', 'sessions', userId],
    enabled: !!userId,
    queryFn: () => unwrap(api.GET('/api/security/sessions', { params: { query: { userId: userId! } } })),
  })
  const rows = sessions.data?.sessions ?? []
  const minutes = effectiveMinutes(sessions.data?.effectiveWithinSeconds)

  const open = (session: SessionInfo | null) => {
    setReason('')
    setReasonError(null)
    setTarget({ session })
  }

  const confirm = async () => {
    if (!target || !userId) return
    if (reason.trim() === '') {
      setReasonError(t('sessions.reasonRequired'))
      return
    }
    setBusy(true)
    try {
      const output = await runProcess<{ revoked?: number }>(REVOKE_PROCESS, {
        userId,
        ...(target.session ? { sessionId: target.session.id } : {}),
        reason: reason.trim(),
      })
      notify.success(t('sessions.revoked', { count: output?.revoked ?? 0 }))
      setTarget(null)
      await sessions.refetch()
    } catch (e) {
      notify.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }

  const columns: ColumnDef<SessionInfo, unknown>[] = [
    {
      id: 'device',
      header: t('sessions.device'),
      cell: ({ row: { original: s } }) => (
        <span className="inline-flex items-center gap-2">
          <SessionDeviceIcon session={s} className="size-4" />
          <span title={s.userAgent ?? undefined}>{sessionDeviceName(tAuth, s)}</span>
          {s.mfa && <Badge variant="outline">{tAuth('sessions.mfa')}</Badge>}
          {s.oidc && <Badge variant="outline">{tAuth('sessions.oidc')}</Badge>}
        </span>
      ),
    },
    { id: 'entry', header: t('sessions.entry'), cell: ({ row }) => <code className="font-mono text-xs">{row.original.entry}</code> },
    { id: 'address', header: t('sessions.address'), cell: ({ row }) => row.original.clientIp ?? '-' },
    { id: 'lastUsed', header: t('sessions.lastUsed'), cell: ({ row }) => formatDateTime(row.original.lastUsedAt) },
    { id: 'created', header: t('sessions.created'), cell: ({ row }) => formatDateTime(row.original.createdAt) },
    { id: 'expires', header: t('sessions.expires'), cell: ({ row }) => formatDateTime(row.original.expiresAt) },
    ...(mayRevoke
      ? [
          {
            id: 'actions',
            header: () => <span className="sr-only">{t('sessions.revoke')}</span>,
            cell: ({ row: { original: s } }) => (
              <Button variant="outline" size="sm" onClick={() => open(s)} data-testid="admin-session-revoke">
                {t('sessions.revoke')}
              </Button>
            ),
          } satisfies ColumnDef<SessionInfo, unknown>,
        ]
      : []),
  ]

  return (
    <AdminPage
      title={t('sessions.title')}
      description={t('sessions.hint', { minutes })}
      actions={
        mayRevoke && rows.length > 0 ? (
          <Button variant="destructive" onClick={() => open(null)} data-testid="admin-session-revoke-all">
            {t('sessions.revokeAll')}
          </Button>
        ) : undefined
      }
    >
      <div className="flex max-w-md flex-col gap-2">
        <Label htmlFor={userField}>{t('sessions.user')}</Label>
        <UserPicker id={userField} value={userId} onChange={setUserId} />
      </div>
      {userId && (
        <DataTable
          label={t('sessions.title')}
          columns={columns}
          getRowId={(s) => s.id ?? ''}
          loading={sessions.isLoading}
          empty={sessions.error ? (sessions.error instanceof ApiError ? sessions.error.display : String(sessions.error)) : t('sessions.empty')}
          data={rows}
        />
      )}
      {sessions.data?.truncated && (
        <p className="text-muted-foreground text-xs">{t('sessions.truncated', { count: rows.length })}</p>
      )}
      <Dialog open={!!target} onOpenChange={(next) => !next && !busy && setTarget(null)}>
        <DialogContent className="sm:max-w-md">
          <form
            noValidate
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              void confirm()
            }}
          >
            <DialogHeader>
              <DialogTitle>{target?.session ? t('sessions.revokeTitle') : t('sessions.revokeAllTitle')}</DialogTitle>
              <DialogDescription>
                {target?.session ? sessionDeviceName(tAuth, target.session) : null}
                {target?.session ? ' · ' : null}
                {t('sessions.hint', { minutes })}
              </DialogDescription>
            </DialogHeader>
            <div className="flex flex-col gap-2">
              <Label htmlFor={reasonField}>{t('sessions.reason')}</Label>
              <Textarea
                id={reasonField}
                value={reason}
                maxLength={500}
                required
                aria-invalid={reasonError ? true : undefined}
                aria-describedby={`${reasonField}-hint`}
                onChange={(event) => setReason(event.target.value)}
                data-testid="admin-session-reason"
              />
              <p id={`${reasonField}-hint`} className={reasonError ? 'text-destructive text-sm' : 'text-muted-foreground text-sm'}>
                {reasonError ?? t('sessions.reasonHint')}
              </p>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setTarget(null)} disabled={busy}>
                {t('sessions.cancel')}
              </Button>
              <Button type="submit" variant="destructive" disabled={busy} data-testid="admin-session-confirm">
                {t('sessions.confirm')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </AdminPage>
  )
}
