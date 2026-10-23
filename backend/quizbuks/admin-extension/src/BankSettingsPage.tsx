import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Alert,
  AlertDescription,
  ApiError,
  Button,
  DescriptionList,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  PageHeader,
  PageState,
  Textarea,
  UserName,
  useAuth,
} from '@jabiz/admin'
import { LoadFailed, Section } from './components'
import { PERMISSION } from './ids'
import {
  BANK_SETTING_KEYS,
  FIELD_OF,
  proposeBankSettings,
  publishProposals,
  readBankSettings,
  readOpenProposals,
  type BankSettings,
  type BankSettingsInput,
  type OpenProposal,
} from './settings-api'

const QUERY = ['qb-ext', 'bank-settings'] as const

const problemOf = (error: unknown, failed: string) =>
  error instanceof ApiError ? error.display : failed

/** The form: every value, starting from the ones in force, and why (`QB_BANK_SETTINGS_PROPOSE`). */
function ProposeForm({ current, onProposed }: { current: BankSettings; onProposed: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const [values, setValues] = useState<BankSettingsInput>(() => Object.fromEntries(BANK_SETTING_KEYS.map((key) =>
    [FIELD_OF[key], current[key] ?? ''])) as unknown as BankSettingsInput)
  const [reason, setReason] = useState('')
  const propose = useMutation({ mutationFn: () => proposeBankSettings(values, reason), onSuccess: onProposed })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    propose.mutate()
  }
  return (
    <form className="flex max-w-xl flex-col gap-3" onSubmit={submit} data-testid="bank-settings-form">
      <p className="text-muted-foreground text-sm">{t('bankSettings.proposeHint')}</p>
      {BANK_SETTING_KEYS.map((key) => {
        const field = FIELD_OF[key]
        const amount = field === 'min' || field === 'max'
        return (
          <div key={key} className="flex flex-col gap-1">
            <Label htmlFor={`${id}-${field}`}>{t(`bankSettings.keys.${field}`)}</Label>
            <Input id={`${id}-${field}`} value={values[field]} required autoComplete="off"
              inputMode={amount ? 'numeric' : undefined} maxLength={amount ? 17 : field === 'accountType'
                || field === 'accountNumber' ? 20 : 100}
              data-testid={`bank-settings-${field}`}
              onChange={(event) => setValues({ ...values, [field]: event.target.value })} />
          </div>
        )
      })}
      <div className="flex flex-col gap-1">
        <Label htmlFor={`${id}-reason`}>{t('bankSettings.reason')}</Label>
        <Textarea id={`${id}-reason`} value={reason} required maxLength={500} data-testid="bank-settings-reason"
          onChange={(event) => setReason(event.target.value)} />
      </div>
      {propose.isError && (
        <Alert variant="destructive" data-testid="bank-settings-problem">
          <AlertDescription>{problemOf(propose.error, t('common.failed'))}</AlertDescription>
        </Alert>
      )}
      {propose.isSuccess && (
        <p role="status" className="text-sm" data-testid="bank-settings-proposed">{t('bankSettings.proposed')}</p>
      )}
      <div>
        <Button type="submit" disabled={propose.isPending} aria-busy={propose.isPending || undefined}
          data-testid="bank-settings-propose">
          {t('bankSettings.submit')}
        </Button>
      </div>
    </form>
  )
}

/** The open proposals beside the values in force; another administrator publishes them all at once. */
function OpenProposals({ open, current, onPublished }: {
  open: OpenProposal[]
  current: BankSettings
  onPublished: () => void
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can, userId } = useAuth()
  const others = open.filter((proposal) => proposal.proposedBy !== userId)
  const publish = useMutation({ mutationFn: () => publishProposals(others.map((p) => p.changeId)),
    onSettled: onPublished })
  if (open.length === 0) return <p className="text-muted-foreground text-sm">{t('bankSettings.none')}</p>
  return (
    <div className="flex flex-col gap-3">
      <ul className="flex flex-col gap-2" data-testid="bank-settings-open">
        {open.map((proposal) => (
          <li key={proposal.changeId} className="rounded-md border p-3 text-sm" data-testid="bank-settings-proposal"
            data-key={proposal.key}>
            <p className="font-medium">{t(`bankSettings.keys.${FIELD_OF[proposal.key]}`)}</p>
            <DescriptionList items={[
              { key: 'current', label: t('bankSettings.current'), value: current[proposal.key] ?? t('bankSettings.notSet') },
              { key: 'proposed', label: t('bankSettings.proposedValue'),
                value: <strong>{proposal.value ?? t('bankSettings.notSet')}</strong> },
              { key: 'by', label: t('bankSettings.proposer'), value: <UserName id={proposal.proposedBy} /> },
              { key: 'reason', label: t('bankSettings.reason'), value: proposal.reason },
            ]} />
            {proposal.proposedBy === userId && (
              <p className="text-muted-foreground mt-1">{t('bankSettings.yours')}</p>
            )}
          </li>
        ))}
      </ul>
      {can(PERMISSION.controlPublish) && others.length > 0 && (
        <div className="flex flex-col gap-2">
          <p className="text-muted-foreground text-sm" id="bank-settings-publish-hint">{t('bankSettings.publishHint')}</p>
          <div>
            <Button disabled={publish.isPending} aria-busy={publish.isPending || undefined}
              aria-describedby="bank-settings-publish-hint" data-testid="bank-settings-publish"
              onClick={() => publish.mutate()}>
              {t('bankSettings.publish')}
            </Button>
          </div>
        </div>
      )}
      {publish.isError && (
        <Alert variant="destructive" data-testid="bank-settings-publish-problem">
          <AlertDescription>{problemOf(publish.error, t('common.failed'))}</AlertDescription>
        </Alert>
      )}
      {publish.isSuccess && (
        <p role="status" className="text-sm" data-testid="bank-settings-published">{t('bankSettings.published')}</p>
      )}
    </div>
  )
}

/**
 * The bank top-up's settings (Q18a): the receiving account and the limits in force, a form that proposes all of them
 * at once, and the proposals waiting to be published, each beside the value in force, published together by another
 * administrator. The server decides who may do each (four eyes, platform decision D40).
 */
export default function BankSettingsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const settings = useQuery({ queryKey: [...QUERY, 'current'], queryFn: readBankSettings })
  const proposer = can(PERMISSION.controlPropose)
  const publisher = can(PERMISSION.controlPublish)
  const open = useQuery({ queryKey: [...QUERY, 'open'], queryFn: readOpenProposals, enabled: proposer || publisher })
  const reload = () => void queryClient.invalidateQueries({ queryKey: QUERY })

  if (settings.isPending) return <PageState kind="loading" />
  if (settings.isError) return <LoadFailed error={settings.error} onRetry={() => void settings.refetch()} />
  const current = settings.data
  const ready = BANK_SETTING_KEYS.every((key) => current[key] !== null)
  return (
    <div className="flex flex-col gap-4" data-testid="qb-bank-settings">
      <PageHeader title={t('bankSettings.title')} description={t('bankSettings.description')} className="pb-0" />
      <Section title={t('bankSettings.current')} testId="bank-settings-current">
        <p className="text-sm" data-testid="bank-settings-ready" data-ready={ready}>
          {ready ? t('bankSettings.ready') : t('bankSettings.notReady')}
        </p>
        <DescriptionList columns={2} items={BANK_SETTING_KEYS.map((key) => ({ key,
          label: t(`bankSettings.keys.${FIELD_OF[key]}`), value: current[key] ?? t('bankSettings.notSet') }))} />
      </Section>
      {(proposer || publisher) && (
        <Section title={t('bankSettings.open')} testId="bank-settings-open-section">
          {open.isPending ? <PageState kind="loading" />
            : open.isError ? <LoadFailed error={open.error} onRetry={() => void open.refetch()} />
              : <OpenProposals open={open.data} current={current} onPublished={reload} />}
        </Section>
      )}
      {proposer && (
        <Section title={t('bankSettings.propose')} testId="bank-settings-propose-section">
          <ProposeForm current={current} onProposed={reload} />
        </Section>
      )}
      {!proposer && !publisher && <p className="text-muted-foreground text-sm">{t('bankSettings.notAllowed')}</p>}
    </div>
  )
}
