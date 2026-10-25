import { formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Badge,
  Button,
  ConfirmDialog,
  Input,
  Label,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { fieldProblem, generalProblem, problemText } from '../qb/problems'
import {
  MAIL_SERVER_TLS,
  readMailServer,
  removeMailServer,
  saveMailServer,
  type MailServer,
  type MailServerOutput,
  type MailServerTls,
} from './api'
import { MAIL_SERVER_QUERY } from './paths'

const FIELDS = ['host', 'port', 'tls', 'username', 'password', 'fromAddress', 'fromName'] as const

/** The sponsor's own mail server (Q29), which the invitations page reads too. */
export function useMailServer() {
  return useQuery({ queryKey: MAIL_SERVER_QUERY, queryFn: readMailServer })
}

/**
 * The sponsor's own mail server (docs/quizbuks/plans/Q29-merchant-mail-server.md; template `qb.sponsor.mail-server`):
 * the settings their invitations leave through. Saving sends a test mail through the server to the sponsor's own
 * address (`QB_MAIL_SERVER_SET`); the page shows whether it left, or the server's answer. The password is typed to
 * set it and left blank to keep it; it is never shown again.
 */
export function MailServerPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const server = useMailServer()
  // The last save's answer, held here: the form below is remounted when the row first appears (its key), and the
  // answer to the save that created the row must survive that.
  const [result, setResult] = useState<MailServerOutput | null>(null)
  if (server.isPending) return <PageState kind="loading" />
  if (server.isError) {
    return <PageState kind="error" description={problemText(server.error)}
      action={<Button variant="outline" onClick={() => void server.refetch()}>{tQb('retry')}</Button>} />
  }
  return (
    <div className="flex flex-col gap-6" data-testid="mail-server-page">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold">{t('publishing.mailServer.title')}</h1>
        <p className="text-muted-foreground text-sm">{t('publishing.mailServer.intro')}</p>
      </div>
      <MailServerStatus server={server.data} />
      <MailServerForm key={server.data ? 'saved' : 'none'} server={server.data} result={result} onResult={setResult} />
    </div>
  )
}

/** Where the server stands: none yet, verified since, or refused with the server's answer. */
function MailServerStatus({ server }: { server: MailServer | null }) {
  const { t } = useTranslation(SPONSOR)
  if (!server) {
    return <p className="text-muted-foreground text-sm" data-testid="mail-server-status" data-status="NONE">
      {t('publishing.mailServer.none')}
    </p>
  }
  if (server.verifiedAt) {
    return (
      <div className="flex flex-wrap items-center gap-2 text-sm" data-testid="mail-server-status" data-status="VERIFIED">
        <Badge variant="success">{t('publishing.mailServer.verified')}</Badge>
        <span className="text-muted-foreground">
          {t('publishing.mailServer.verifiedAt', { time: formatDateTime(server.verifiedAt) })}
        </span>
      </div>
    )
  }
  return (
    <Alert variant="destructive" data-testid="mail-server-status" data-status="FAILED">
      <AlertTitle>{t('publishing.mailServer.failed')}</AlertTitle>
      <AlertDescription>
        <p>{server.lastError ?? t('publishing.mailServer.failedUnknown')}</p>
        {server.testedAt && <p>{t('publishing.mailServer.testedAt', { time: formatDateTime(server.testedAt) })}</p>}
      </AlertDescription>
    </Alert>
  )
}

function MailServerForm({ server, result, onResult }: {
  server: MailServer | null
  result: MailServerOutput | null
  onResult: (result: MailServerOutput | null) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const id = useId()
  const [host, setHost] = useState(server?.host ?? '')
  const [port, setPort] = useState(server ? String(server.port) : '587')
  const [tls, setTls] = useState<MailServerTls>(server?.tls ?? 'STARTTLS')
  const [username, setUsername] = useState(server?.username ?? '')
  const [password, setPassword] = useState('')
  const [fromAddress, setFromAddress] = useState(server?.fromAddress ?? '')
  const [fromName, setFromName] = useState(server?.fromName ?? '')
  const [confirmRemove, setConfirmRemove] = useState(false)

  const save = useMutation({
    mutationFn: () => saveMailServer({
      host: host.trim(),
      port: Number(port),
      tls,
      username: username.trim(),
      password: password === '' ? null : password,
      fromAddress: fromAddress.trim(),
      fromName: fromName.trim() === '' ? null : fromName.trim(),
    }),
    onSuccess: async (output) => {
      onResult(output)
      setPassword('')
      await queryClient.invalidateQueries({ queryKey: MAIL_SERVER_QUERY })
    },
  })
  const remove = useMutation({
    mutationFn: removeMailServer,
    onSuccess: async () => {
      onResult(null)
      await queryClient.invalidateQueries({ queryKey: MAIL_SERVER_QUERY })
    },
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    onResult(null)
    save.mutate()
  }
  const problem = (field: string) => fieldProblem(save.error, field)
  const errorId = (field: string) => `${id}-${field}-error`
  const describedBy = (field: string, hint?: string) =>
    [hint, problem(field) ? errorId(field) : undefined].filter(Boolean).join(' ') || undefined
  const invalid = (field: string) => (problem(field) ? true : undefined)
  const errorOf = (field: string) => problem(field)
    && <p id={errorId(field)} className="text-destructive text-sm">{problem(field)}</p>
  const general = generalProblem(save.error, FIELDS) ?? (remove.isError ? problemText(remove.error) : undefined)
  const portValid = /^[0-9]{1,5}$/.test(port) && Number(port) >= 1 && Number(port) <= 65535

  return (
    <form noValidate onSubmit={submit} className="flex max-w-xl flex-col gap-4" data-testid="mail-server-form">
      <div className="grid gap-4 sm:grid-cols-[1fr_8rem]">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-host`}>{t('publishing.mailServer.host')}</Label>
          <Input id={`${id}-host`} value={host} required maxLength={253} autoComplete="off" inputMode="url"
            placeholder="smtp.example.com" aria-invalid={invalid('host')} aria-describedby={describedBy('host')}
            data-testid="mail-server-host" onChange={(event) => setHost(event.target.value)} />
          {errorOf('host')}
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-port`}>{t('publishing.mailServer.port')}</Label>
          <Input id={`${id}-port`} value={port} required inputMode="numeric" pattern="[0-9]*" maxLength={5}
            aria-invalid={invalid('port') || (!portValid && port !== '') || undefined}
            aria-describedby={describedBy('port', `${id}-port-hint`)} data-testid="mail-server-port"
            onChange={(event) => setPort(event.target.value)} />
          <p id={`${id}-port-hint`} className="text-muted-foreground text-sm">{t('publishing.mailServer.portHint')}</p>
          {errorOf('port')}
        </div>
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-tls`}>{t('publishing.mailServer.tls')}</Label>
        <Select value={tls} onValueChange={(value) => setTls(value as MailServerTls)}>
          <SelectTrigger id={`${id}-tls`} className="w-64" aria-invalid={invalid('tls')}
            aria-describedby={describedBy('tls', `${id}-tls-hint`)} data-testid="mail-server-tls">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {MAIL_SERVER_TLS.map((mode) => (
              <SelectItem key={mode} value={mode}>{t(`publishing.mailServer.tlsModes.${mode}`)}</SelectItem>
            ))}
          </SelectContent>
        </Select>
        <p id={`${id}-tls-hint`} className="text-muted-foreground text-sm">{t('publishing.mailServer.tlsHint')}</p>
        {errorOf('tls')}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-username`}>{t('publishing.mailServer.username')}</Label>
        <Input id={`${id}-username`} value={username} required maxLength={200} autoComplete="off"
          aria-invalid={invalid('username')} aria-describedby={describedBy('username')}
          data-testid="mail-server-username" onChange={(event) => setUsername(event.target.value)} />
        {errorOf('username')}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-password`}>{t('publishing.mailServer.password')}</Label>
        <Input id={`${id}-password`} type="password" value={password} maxLength={200} autoComplete="new-password"
          required={!server} aria-invalid={invalid('password')}
          aria-describedby={describedBy('password', server ? `${id}-password-hint` : undefined)}
          data-testid="mail-server-password" onChange={(event) => setPassword(event.target.value)} />
        {server && (
          <p id={`${id}-password-hint`} className="text-muted-foreground text-sm">
            {t('publishing.mailServer.passwordKept')}
          </p>
        )}
        {errorOf('password')}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-from`}>{t('publishing.mailServer.fromAddress')}</Label>
        <Input id={`${id}-from`} type="email" value={fromAddress} required maxLength={320} autoComplete="off"
          aria-invalid={invalid('fromAddress')} aria-describedby={describedBy('fromAddress', `${id}-from-hint`)}
          data-testid="mail-server-from" onChange={(event) => setFromAddress(event.target.value)} />
        <p id={`${id}-from-hint`} className="text-muted-foreground text-sm">{t('publishing.mailServer.fromHint')}</p>
        {errorOf('fromAddress')}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-from-name`}>{t('publishing.mailServer.fromName')}</Label>
        <Input id={`${id}-from-name`} value={fromName} maxLength={100} autoComplete="organization"
          aria-invalid={invalid('fromName')} aria-describedby={describedBy('fromName')}
          data-testid="mail-server-from-name" onChange={(event) => setFromName(event.target.value)} />
        {errorOf('fromName')}
      </div>
      {general && (
        <Alert variant="destructive" data-testid="mail-server-error">
          <AlertDescription>{general}</AlertDescription>
        </Alert>
      )}
      {result && !save.isPending && (result.verified ? (
        <p role="status" className="text-success flex items-center gap-2 text-sm" data-testid="mail-server-result"
          data-verified="true">
          <CircleCheck aria-hidden className="size-4" />
          {t('publishing.mailServer.testSent', { address: result.sentTo ?? '' })}
        </p>
      ) : (
        <Alert variant="destructive" role="status" data-testid="mail-server-result" data-verified="false">
          <AlertTitle>{t('publishing.mailServer.testFailed')}</AlertTitle>
          <AlertDescription>{result.error ?? t('publishing.mailServer.failedUnknown')}</AlertDescription>
        </Alert>
      ))}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={save.isPending || remove.isPending || !portValid}
          aria-busy={save.isPending || undefined} data-testid="mail-server-save">
          {save.isPending ? t('publishing.mailServer.saving') : t('publishing.mailServer.save')}
        </Button>
        {server && (
          <Button type="button" variant="outline" disabled={save.isPending || remove.isPending}
            data-testid="mail-server-remove" onClick={() => setConfirmRemove(true)}>
            {t('publishing.mailServer.remove')}
          </Button>
        )}
      </div>
      <ConfirmDialog open={confirmRemove} title={t('publishing.mailServer.removeTitle')}
        description={t('publishing.mailServer.removeText')} confirmLabel={t('publishing.mailServer.remove')}
        cancelLabel={t('common.cancel')} destructive onConfirm={() => remove.mutateAsync()}
        onOpenChange={setConfirmRemove} />
    </form>
  )
}
