import { Checkbox, Input, Label, RadioGroup, RadioGroupItem, Textarea } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import {
  REFEREE_RULES,
  REFERRER_REQUIREMENTS,
  type PublicationForm,
  type ReferrerRequirement,
  type RewardMethod,
} from './api'
import { useRewardMethodLabel } from './shared'

const DESCRIPTION_LENGTH = 500

/**
 * "Referrals welcome" in the wizard (Q70, D-Q70-10): whether the publication welcomes referrals, how many, what a
 * friend's pass earns the referrer and how, what the referrer must have done, who may be referred (the sponsor's own
 * rules: the platform adds none), and for how long. Every check is the server's; the problems come back as the save's
 * (`referral.<field>`).
 */
export function ReferralFields({ id, form, set, locked, problemOf, methods }: {
  id: string
  form: PublicationForm
  set: <K extends keyof PublicationForm>(field: K, value: PublicationForm[K]) => void
  locked: boolean
  problemOf: (field: string) => string | undefined
  methods: RewardMethod[]
}) {
  const { t } = useTranslation(SPONSOR)
  const methodLabel = useRewardMethodLabel()
  const invited = form.audience === 'INVITED'
  const problem = (field: string) => problemOf(`referral.${field}`)
  const describe = form.referralMethod === 'OFFLINE_REDEEM' || form.referralMethod === 'DIGITAL_PRIZE'
  const toggleRule = (rule: string, on: boolean) => set('refereeRules',
    on ? [...form.refereeRules.filter((r) => r !== rule), rule] : form.refereeRules.filter((r) => r !== rule))
  return (
    <fieldset className="flex flex-col gap-3 rounded-lg border p-4" data-testid="publication-referral">
      <legend className="px-1 text-sm font-medium">{t('publishing.referral.title')}</legend>
      <div className="flex items-start gap-2">
        <Checkbox id={`${id}-referral`} checked={form.referralEnabled} disabled={locked || invited}
          onCheckedChange={(value) => set('referralEnabled', value === true)} data-testid="publication-referral-on"
          aria-describedby={`${id}-referral-hint`} />
        <div className="flex flex-col">
          <Label htmlFor={`${id}-referral`} className="font-normal">{t('publishing.referral.enable')}</Label>
          <span id={`${id}-referral-hint`} className="text-muted-foreground text-xs">
            {invited ? t('publishing.referral.notInvited') : t('publishing.referral.enableHint')}
          </span>
        </div>
      </div>
      {problemOf('referral') && <p className="text-destructive text-sm">{problemOf('referral')}</p>}
      {form.referralEnabled && (
        <div className="flex flex-col gap-4">
          <div className="grid gap-4 sm:grid-cols-3">
            <NumberField id={`${id}-referral-quota`} label={t('publishing.referral.quota')}
              hint={t('publishing.referral.quotaHint')} value={form.referralQuota} problem={problem('quota')}
              disabled={locked} testId="publication-referral-quota" onChange={(v) => set('referralQuota', v)} />
            <NumberField id={`${id}-referral-amount`} label={t('publishing.referral.amount')}
              hint={t('publishing.referral.amountHint')} value={form.referralAmount} problem={problem('amount')}
              disabled={locked} testId="publication-referral-amount" onChange={(v) => set('referralAmount', v)} />
            <NumberField id={`${id}-referral-days`} label={t('publishing.referral.days')}
              hint={t('publishing.referral.daysHint')} value={form.referralDays} problem={problem('days')}
              disabled={locked} testId="publication-referral-days" onChange={(v) => set('referralDays', v)} />
          </div>
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">{t('publishing.referral.method')}</legend>
            <RadioGroup value={form.referralMethod} disabled={locked}
              onValueChange={(value) => set('referralMethod', value as RewardMethod)}>
              {methods.map((method) => (
                <div key={method} className="flex items-center gap-2">
                  <RadioGroupItem id={`${id}-referral-method-${method}`} value={method}
                    data-testid={`publication-referral-method-${method}`} />
                  <Label htmlFor={`${id}-referral-method-${method}`} className="font-normal">{methodLabel(method)}</Label>
                </div>
              ))}
            </RadioGroup>
            {problem('method') && <p className="text-destructive text-sm">{problem('method')}</p>}
          </fieldset>
          {describe && (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={`${id}-referral-description`}>{t('publishing.referral.description')}</Label>
              <Textarea id={`${id}-referral-description`} value={form.referralDescription} rows={2}
                maxLength={DESCRIPTION_LENGTH} disabled={locked} data-testid="publication-referral-description"
                aria-invalid={problem('description') ? true : undefined}
                onChange={(event) => set('referralDescription', event.target.value)} />
              {problem('description') && <p className="text-destructive text-sm">{problem('description')}</p>}
            </div>
          )}
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">{t('publishing.referral.requires')}</legend>
            <RadioGroup value={form.referrerRequires} disabled={locked}
              onValueChange={(value) => set('referrerRequires', value as ReferrerRequirement)}>
              {REFERRER_REQUIREMENTS.map((requirement) => (
                <div key={requirement} className="flex items-center gap-2">
                  <RadioGroupItem id={`${id}-referral-requires-${requirement}`} value={requirement}
                    data-testid={`publication-referral-requires-${requirement}`} />
                  <Label htmlFor={`${id}-referral-requires-${requirement}`} className="font-normal">
                    {t(`publishing.referral.requirement.${requirement}`)}
                  </Label>
                </div>
              ))}
            </RadioGroup>
            {problem('referrerRequires') && <p className="text-destructive text-sm">{problem('referrerRequires')}</p>}
          </fieldset>
          <fieldset className="flex flex-col gap-2" aria-describedby={`${id}-referral-rules-hint`}>
            <legend className="text-sm font-medium">{t('publishing.referral.rules')}</legend>
            <p id={`${id}-referral-rules-hint`} className="text-muted-foreground text-xs">
              {t('publishing.referral.rulesHint')}
            </p>
            {REFEREE_RULES.map((rule) => (
              <div key={rule} className="flex items-center gap-2">
                <Checkbox id={`${id}-referral-rule-${rule}`} checked={form.refereeRules.includes(rule)} disabled={locked}
                  onCheckedChange={(value) => toggleRule(rule, value === true)}
                  data-testid={`publication-referral-rule-${rule}`} />
                <Label htmlFor={`${id}-referral-rule-${rule}`} className="font-normal">
                  {t(`publishing.referral.rule.${rule}`)}
                </Label>
              </div>
            ))}
            {problem('refereeRules') && <p className="text-destructive text-sm">{problem('refereeRules')}</p>}
          </fieldset>
        </div>
      )}
    </fieldset>
  )
}

function NumberField({ id, label, hint, value, problem, disabled, testId, onChange }: {
  id: string; label: string; hint: string; value: string; problem?: string; disabled: boolean; testId: string
  onChange: (value: string) => void
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Input id={id} inputMode="numeric" value={value} autoComplete="off" disabled={disabled} data-testid={testId}
        aria-describedby={`${id}-hint`} aria-invalid={problem ? true : undefined}
        onChange={(event) => onChange(event.target.value)} />
      <span id={`${id}-hint`} className="text-muted-foreground text-xs">{hint}</span>
      {problem && <span className="text-destructive text-sm">{problem}</span>}
    </div>
  )
}
