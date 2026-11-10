import { useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Button,
  DataTable,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  PageHeader,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { readCertificateLookup, type CertificateRow } from './api'
import { useCertificateName } from './certificate-state'
import { CertificateStateBadge } from './certificate-view'
import { EmptyState, LoadFailed, Section } from './components'
import { usePaged, when } from './hooks'
import { PATH, PERMISSION } from './ids'
import { UserPicker } from './pickers'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const KEY_SHAPE = /^[0-9a-f]{32}$/
/** The verify page of the app (the address printed on the certificate), below this site's /taker/. */
const verifyHref = (verifyKey: string) => `/taker/verify/${encodeURIComponent(verifyKey)}`

/**
 * The certificate lookup (F-S5b, docs/quizbuks/plans/impl/F-S5.md section 3): by the number a verifier quotes, or by the
 * holder, with each certificate's state (valid, revoked, its link turned off) and the way to the map where the lit
 * record it came from is. The platform's support reads every certificate (`qb.admin.certificates`); an operator's member
 * only those of the maps its operator looks after (`qb.operator.certificates`: another's, and the platform's, are not
 * listed). A member without the users' page types the holder's user ID.
 */
export default function CertificatesPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const operator = !can(PERMISSION.usersRead) && can(PERMISSION.operatorUse)
  const nameOf = useCertificateName()
  const [pickedUser, setPickedUser] = useState<string | null>(null)
  const [typedUser, setTypedUser] = useState('')
  const [typedNumber, setTypedNumber] = useState('')
  const [search, setSearch] = useState<{ userId: string | null; verifyKey: string | null }>({ userId: null,
    verifyKey: null })
  const holder = can(PERMISSION.usersRead) ? pickedUser : typedUser.trim() || null
  // The number as printed may be in capitals or have spaces; the key is 32 lowercase hex digits.
  const number = typedNumber.replace(/\s+/g, '').toLowerCase()
  const numberInvalid = number !== '' && !KEY_SHAPE.test(number)
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!numberInvalid) setSearch({ userId: holder, verifyKey: number || null })
  }
  const clear = () => {
    setPickedUser(null)
    setTypedUser('')
    setTypedNumber('')
    setSearch({ userId: null, verifyKey: null })
  }
  const { query, table } = usePaged(['qb-ext', 'certificate-lookup', operator, search],
    (offset, limit, sorts) => readCertificateLookup(operator, search, offset, limit, sorts))
  const columns = useMemo<ColumnDef<CertificateRow, unknown>[]>(() => [
    { id: 'name', header: t('certificateLookup.columns.name'), cell: ({ row }) => (
      <span className="flex flex-col">
        <span className="font-medium" data-testid="certificate-name">{nameOf(row.original)}</span>
        <span className="text-muted-foreground text-sm">{t(`certificateLookup.sources.${row.original.source}`,
          { defaultValue: row.original.source })}</span>
      </span>
    ) },
    { id: 'holder', header: t('certificateLookup.columns.holder'), cell: ({ row }) => (
      <span className="flex flex-col">
        <span>{row.original.holderName}</span>
        {can(PERMISSION.usersRead) && <UserName id={row.original.userId} />}
      </span>
    ) },
    { id: 'kind', header: t('certificateLookup.columns.kind'), cell: ({ row }) => t(
      `certificateLookup.kinds.${row.original.subjectKind}`, { defaultValue: row.original.subjectKind }) },
    { id: 'issuer', header: t('certificateLookup.columns.issuer'), cell: ({ row }) => row.original.issuerName ?? '' },
    { id: 'lit', header: t('certificateLookup.columns.lit'), cell: ({ row }) => when(row.original.heldFrom) },
    { id: 'number', header: t('certificateLookup.columns.number'), cell: ({ row }) => (
      <span className="font-mono text-xs break-all" data-testid="certificate-number">{row.original.verifyKey}</span>
    ) },
    { id: 'state', header: t('certificateLookup.columns.state'), cell: ({ row }) => (
      <CertificateStateBadge row={row.original} />
    ) },
    { id: 'links', header: t('certificateLookup.columns.links'), cell: ({ row }) => {
      const name = nameOf(row.original)
      return (
        <span className="flex flex-col gap-1">
          {row.original.treeId && (
            <Link to={PATH.tree(row.original.treeId)} className={linkClass} data-testid="certificate-lit-link"
              aria-label={t('certificateLookup.litRecordFor', { name })}>{t('certificateLookup.litRecord')}</Link>
          )}
          <a href={verifyHref(row.original.verifyKey)} className={linkClass} target="_blank" rel="noreferrer"
            data-testid="certificate-verify-link"
            aria-label={t('certificateLookup.verifyPageFor', { name })}>{t('certificateLookup.verifyPage')}</a>
        </span>
      )
    } },
  ], [t, nameOf, can])
  return (
    <div className="flex flex-col gap-4" data-testid="qb-certificates">
      <PageHeader title={t('certificateLookup.title')} className="pb-0"
        description={t(operator ? 'certificateLookup.descriptionOperator' : 'certificateLookup.description')} />
      <Section title={t('certificateLookup.title')}>
        <form noValidate onSubmit={submit} className="grid gap-3 sm:grid-cols-[1fr_1fr_auto_auto] sm:items-start">
          <div className="flex flex-col gap-2">
            <Label htmlFor="certificate-lookup-number">{t('certificateLookup.number')}</Label>
            <Input id="certificate-lookup-number" value={typedNumber} autoComplete="off" spellCheck={false}
              aria-invalid={numberInvalid} aria-describedby="certificate-lookup-number-hint"
              data-testid="certificate-lookup-number" onChange={(event) => setTypedNumber(event.target.value)} />
            <p id="certificate-lookup-number-hint" className={numberInvalid ? 'text-destructive text-sm'
              : 'text-muted-foreground text-sm'}>{t('certificateLookup.numberHint')}</p>
          </div>
          <div className="flex flex-col gap-2">
            {can(PERMISSION.usersRead) ? (
              <>
                <Label htmlFor="certificate-lookup-user">{t('certificateLookup.user')}</Label>
                <UserPicker id="certificate-lookup-user" value={pickedUser} onChange={setPickedUser} clearable
                  data-testid="certificate-lookup-user" />
              </>
            ) : (
              <>
                <Label htmlFor="certificate-lookup-user">{t('certificateLookup.userId')}</Label>
                <Input id="certificate-lookup-user" value={typedUser} autoComplete="off"
                  data-testid="certificate-lookup-user-id" onChange={(event) => setTypedUser(event.target.value)} />
              </>
            )}
          </div>
          <Button type="submit" disabled={numberInvalid} data-testid="certificate-lookup-submit"
            className="sm:mt-7">{t('certificateLookup.submit')}</Button>
          <Button type="button" variant="outline" onClick={clear} data-testid="certificate-lookup-clear"
            className="sm:mt-7">{t('certificateLookup.clear')}</Button>
        </form>
        <p className="text-muted-foreground text-sm">{t('certificateLookup.note')}</p>
      </Section>
      <Section title={t('certificateLookup.table')} testId="certificate-lookup-result">
        {query.isError ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} /> : (
          <DataTable label={t('certificateLookup.table')} columns={columns} {...table}
            getRowId={(row) => row.certificateId} empty={<EmptyState title={t('certificateLookup.none')} />}
            rowProps={(row) => ({ 'data-testid': 'certificate-row', 'data-id': row.certificateId })} />
        )}
      </Section>
    </div>
  )
}
