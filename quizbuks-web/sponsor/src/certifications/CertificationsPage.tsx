import { formatDate } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Checkbox,
  ConfirmDialog,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  notify,
  PageHeader,
  PageState,
  Textarea,
  type ColumnDef,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { certificationName } from '../qb/certifications'
import type { I18nText } from '../qb/legal'
import { QB } from '../qb/messages'
import { fieldProblem, generalProblem, problemText } from '../qb/problems'
import {
  CERTIFICATIONS_QUERY,
  readMyCertifications,
  retireCertification,
  saveCertification,
  type CertificationInput,
  type SponsorCertification,
} from './api'
import { CERTIFICATION_LIMITS } from './ids'
import './messages'

const LANGUAGES = ['en', 'zh', 'ja'] as const
/** The fields the dialog shows beside their problems; the switch's refusal (on certificationId) is general. */
const FIELDS = ['name', 'description', 'validityDays']

/**
 * The sponsor's own certifications (Q88a, docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md): defined,
 * edited (a new version when the rules changed) and retired here; which quiz gives one is set in the quiz's wizard.
 * The server refuses everything while the switch is off (`QB_SPONSOR_CERTIFICATIONS_OFF`): the page shows its words.
 */
export function CertificationsPage({ embedded = false }: {
  /** F-C1c: a tab of the asset library, under the library's own title (its heading is then a section's). */
  embedded?: boolean
} = {}) {
  const { t, i18n } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const [at] = useState(() => new Date().toISOString())
  const certifications = useQuery({ queryKey: [...CERTIFICATIONS_QUERY, at], queryFn: () => readMyCertifications(at) })
  const [editing, setEditing] = useState<SponsorCertification | 'new' | null>(null)
  const retire = useMutation({
    mutationFn: (certificationId: string) => retireCertification(certificationId),
    onSuccess: () => {
      notify.success(t('certifications.retired'))
      void queryClient.invalidateQueries({ queryKey: CERTIFICATIONS_QUERY })
    },
    onError: (error) => notify.error(problemText(error)),
  })
  const columns = useMemo<ColumnDef<SponsorCertification, unknown>[]>(() => [
    { id: 'name', header: t('certifications.columns.name'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => <span data-testid="certification-name">{certificationName(row.original.key, row.original.name, i18n.language, tQb)}</span> },
    { id: 'validity', header: t('certifications.columns.validity'), enableSorting: false, meta: { card: 'subtitle' },
      cell: ({ row }) => t('certifications.columns.days', { count: row.original.validityDays }) },
    { id: 'version', header: t('certifications.columns.version'), enableSorting: false,
      cell: ({ row }) => row.original.certificationVersion },
    { id: 'rounds', header: t('certifications.columns.rounds'), enableSorting: false,
      cell: ({ row }) => t('certifications.columns.roundsOf', { open: row.original.openRounds, total: row.original.rounds }) },
    { id: 'holders', header: t('certifications.columns.holders'), enableSorting: false,
      meta: { className: 'text-right tabular-nums' },
      cell: ({ row }) => <span data-testid="certification-holders">{row.original.holders}</span> },
    { id: 'status', header: t('certifications.columns.status'), enableSorting: false, meta: { card: 'badge' },
      cell: ({ row }) => <Badge variant={row.original.status === 'ACTIVE' ? 'default' : 'outline'}
        data-testid="certification-status" data-status={row.original.status}>
        {t(`certifications.status.${row.original.status}`)}</Badge> },
    { id: 'actions', header: '', enableSorting: false, meta: { card: 'actions' },
      cell: ({ row }) => row.original.status === 'ACTIVE' && (
        <span className="flex flex-wrap gap-2">
          <Button variant="outline" size="sm" onClick={() => setEditing(row.original)} data-testid="certification-edit">
            {t('certifications.edit')}
          </Button>
          <ConfirmDialog destructive title={t('certifications.retireTitle')} description={t('certifications.retireText')}
            confirmLabel={t('certifications.retire')} onConfirm={() => retire.mutate(row.original.certificationId)}
            trigger={<Button variant="ghost" size="sm" data-testid="certification-retire">{t('certifications.retire')}</Button>} />
        </span>
      ) },
  ], [t, tQb, i18n.language, retire])
  return (
    <section className="flex flex-col gap-4" data-testid="certifications-page">
      {embedded
        ? (
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="flex min-w-0 flex-col gap-1">
              <h2 className="text-xl font-semibold" data-testid="certifications-title">{t('certifications.title')}</h2>
              <p className="text-muted-foreground text-sm">{t('certifications.intro')}</p>
            </div>
            <Button onClick={() => setEditing('new')} data-testid="certification-add">
              <Plus aria-hidden className="size-4" />{t('certifications.add')}</Button>
          </div>
        )
        : <PageHeader title={t('certifications.title')} description={t('certifications.intro')}
          actions={<Button onClick={() => setEditing('new')} data-testid="certification-add">
            <Plus aria-hidden className="size-4" />{t('certifications.add')}</Button>} />}
      {certifications.isPending && <PageState kind="loading" />}
      {certifications.isError && <PageState kind="error" description={problemText(certifications.error)}
        action={<Button variant="outline" onClick={() => void certifications.refetch()}>{tQb('retry')}</Button>} />}
      {certifications.data && (certifications.data.length === 0
        ? <PageState kind="empty" title={t('certifications.empty')} />
        : <DataTable label={t('certifications.title')} columns={columns} data={certifications.data}
          storageKey="qb.sponsor.certifications" getRowId={(row) => row.certificationId}
          rowProps={(row) => ({ 'data-testid': 'certification-row', 'data-id': row.certificationId,
            'data-status': row.status })} />)}
      {editing && <CertificationDialog certification={editing === 'new' ? null : editing}
        onClose={() => setEditing(null)}
        onSaved={() => {
          notify.success(t('certifications.saved'))
          setEditing(null)
          void queryClient.invalidateQueries({ queryKey: CERTIFICATIONS_QUERY })
        }} />}
    </section>
  )
}

function CertificationDialog({ certification, onClose, onSaved }: { certification: SponsorCertification | null;
  onClose: () => void; onSaved: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [form, setForm] = useState<CertificationInput>(() => ({
    certificationId: certification?.certificationId,
    name: { ...certification?.name },
    description: { ...certification?.description },
    validityDays: certification?.validityDays ?? CERTIFICATION_LIMITS.defaultValidityDays,
    raiseVersion: false,
  }))
  const [validity, setValidity] = useState(String(form.validityDays))
  const save = useMutation({ mutationFn: () => saveCertification({ ...form, validityDays: Number(validity) }),
    onSuccess: onSaved })
  const problem = (field: string) => fieldProblem(save.error, field)
  const general = generalProblem(save.error, FIELDS)
  const text = (field: 'name' | 'description', language: string, value: string) =>
    setForm((current) => ({ ...current, [field]: { ...current[field], [language]: value } as I18nText }))
  const submit = (event: FormEvent) => {
    event.preventDefault()
    save.mutate()
  }
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg" data-testid="certification-dialog">
        <DialogHeader>
          <DialogTitle>{certification ? t('certifications.dialog.editTitle') : t('certifications.dialog.newTitle')}</DialogTitle>
          <DialogDescription>{t('certifications.dialog.nameHint')}</DialogDescription>
        </DialogHeader>
        <form id={`${id}-form`} noValidate onSubmit={submit} className="flex flex-col gap-4">
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">{t('certifications.dialog.name')}</legend>
            {LANGUAGES.map((language) => (
              <div key={language} className="flex flex-col gap-1">
                <Label htmlFor={`${id}-name-${language}`} className="font-normal">{t(`certifications.languages.${language}`)}</Label>
                <Input id={`${id}-name-${language}`} value={form.name[language] ?? ''}
                  maxLength={CERTIFICATION_LIMITS.nameLength} lang={language}
                  aria-invalid={problem('name') || problem(`name.${language}`) ? true : undefined}
                  onChange={(event) => text('name', language, event.target.value)}
                  data-testid={`certification-name-${language}`} />
              </div>
            ))}
            {(problem('name') || problem('name.en') || problem('name.zh') || problem('name.ja')) && (
              <p className="text-destructive text-sm" role="alert">
                {problem('name') ?? problem('name.en') ?? problem('name.zh') ?? problem('name.ja')}
              </p>
            )}
          </fieldset>
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">{t('certifications.dialog.description')}</legend>
            <p className="text-muted-foreground text-sm">{t('certifications.dialog.descriptionHint')}</p>
            {LANGUAGES.map((language) => (
              <div key={language} className="flex flex-col gap-1">
                <Label htmlFor={`${id}-description-${language}`} className="font-normal">
                  {t(`certifications.languages.${language}`)}
                </Label>
                <Textarea id={`${id}-description-${language}`} rows={2} value={form.description[language] ?? ''}
                  maxLength={CERTIFICATION_LIMITS.descriptionLength} lang={language}
                  onChange={(event) => text('description', language, event.target.value)}
                  data-testid={`certification-description-${language}`} />
              </div>
            ))}
            {(problem('description.en') || problem('description.zh') || problem('description.ja')) && (
              <p className="text-destructive text-sm" role="alert">
                {problem('description.en') ?? problem('description.zh') ?? problem('description.ja')}
              </p>
            )}
          </fieldset>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-validity`}>{t('certifications.dialog.validityDays')}</Label>
            <Input id={`${id}-validity`} value={validity} inputMode="numeric" className="w-40"
              aria-invalid={problem('validityDays') ? true : undefined} aria-describedby={`${id}-validity-hint`}
              onChange={(event) => setValidity(event.target.value)} data-testid="certification-validity" />
            <p id={`${id}-validity-hint`} className="text-muted-foreground text-sm">
              {t('certifications.dialog.validityHint', { min: CERTIFICATION_LIMITS.minValidityDays,
                max: CERTIFICATION_LIMITS.maxValidityDays })}
            </p>
            {problem('validityDays') && <p className="text-destructive text-sm" role="alert">{problem('validityDays')}</p>}
          </div>
          {certification && (
            <div className="flex flex-col gap-1">
              <div className="flex items-center gap-2">
                <Checkbox id={`${id}-raise`} checked={form.raiseVersion === true}
                  onCheckedChange={(value) => setForm((current) => ({ ...current, raiseVersion: value === true }))}
                  data-testid="certification-raise-version" />
                <Label htmlFor={`${id}-raise`} className="font-normal">{t('certifications.dialog.raiseVersion')}</Label>
              </div>
              <p className="text-muted-foreground ms-6 text-sm">{t('certifications.dialog.raiseVersionHint')}</p>
            </div>
          )}
          {general && (
            <Alert variant="destructive" data-testid="certification-error"><AlertDescription>{general}</AlertDescription></Alert>
          )}
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t('certifications.dialog.cancel')}</Button>
          <Button type="submit" form={`${id}-form`} disabled={save.isPending} data-testid="certification-save">
            {t('certifications.dialog.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** The date a round opened or closed, for the wizard's and the page's words. */
export const roundWhen = (value: string | null) => (value ? formatDate(value) : '')
