import type { ReferralPolicy } from '../qb/play'
import { formatDateTime } from '@jabiz/client'
import {
  Badge,
  DescriptionList,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  type DescriptionItem,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { readVersions } from '../content/api'
import { Thumbnail } from '../content/ContentImage'
import { versionsQuery } from '../content/paths'
import { SPONSOR } from '../messages'
import { criterionOf, type Condition } from '../qb/audience'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import {
  problemPlace,
  readAudiences,
  readEndReasons,
  readInvitationStatuses,
  type Audience,
  readPublicationStatuses,
  readRegions,
  readRewardMethods,
  readCashStatuses,
  readDigitalCodeStatuses,
  readDigitalDeliveries,
  readDigitalStatuses,
  readRedemptionStatuses,
  timeNote,
  type DigitalDelivery,
  type Problem,
  type PublicationStatus,
  type Tier,
  type RewardMethod, type PrerequisiteRow,
} from './api'

/** Waits until typing paused before searching. */
export function useDebounced<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms)
    return () => clearTimeout(timer)
  }, [value, ms])
  return settled
}

/** A dictionary's labels by code (the code itself until the server answered). */
function useLabels(key: string, read: () => Promise<{ code: string; label: string }[]>) {
  const items = useQuery({ queryKey: ['qb', 'dict', key], queryFn: read, staleTime: Infinity })
  return (code: string) => items.data?.find((item) => item.code === code)?.label ?? code
}

export const useStatusLabel = () => useLabels('publication-status', readPublicationStatuses)
export const useEndReasonLabel = () => useLabels('publication-end-reason', readEndReasons)
export const useRegionLabel = () => useLabels('region', readRegions)
export const useRewardMethodLabel = () => useLabels('reward-method', readRewardMethods)
export const useCashStatusLabel = () => useLabels('cash-reward-status', readCashStatuses)
export const useRedemptionStatusLabel = () => useLabels('redemption-status', readRedemptionStatuses)
export const useDigitalDeliveryLabel = () => useLabels('digital-delivery', readDigitalDeliveries)
export const useDigitalStatusLabel = () => useLabels('digital-prize-status', readDigitalStatuses)
export const useDigitalCodeStatusLabel = () => useLabels('digital-code-status', readDigitalCodeStatuses)
export const useAudienceLabel = () => useLabels('publication-audience', readAudiences)
export const useInvitationStatusLabel = () => useLabels('invitation-status', readInvitationStatuses)

const VARIANT: Record<PublicationStatus, 'outline' | 'secondary' | 'success' | 'warning'> = {
  DRAFT: 'outline',
  PENDING_REVIEW: 'secondary',
  SCHEDULED: 'secondary',
  LIVE: 'success',
  PAUSED: 'warning',
  FUNDS_SHORT: 'warning',
  ENDED: 'outline',
}

/**
 * A publication's state as the server names it, with what its times say beyond it (about to start or end: the state
 * follows the times within a minute).
 */
export function StatusBadge({ status, startAt, endAt }: {
  status: PublicationStatus
  startAt: string | null
  endAt: string | null
}) {
  const { t } = useTranslation(SPONSOR)
  const label = useStatusLabel()
  // The time the page was drawn: a note about the next minute need not follow the clock.
  const [now] = useState(Date.now)
  const note = timeNote(status, startAt, endAt, now)
  return (
    <span className="flex flex-wrap items-center gap-1" data-testid="publication-status" data-status={status}>
      <Badge variant={VARIANT[status]}>{label(status)}</Badge>
      {note && <span className="text-muted-foreground text-xs">{t(`publishing.note.${note}`)}</span>}
    </span>
  )
}

/** A publication's running time, or "not set". */
export function Period({ startAt, endAt }: { startAt: string | null; endAt: string | null }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <>
      {t('publishing.period', {
        start: startAt ? formatDateTime(startAt) : t('publishing.notSet'),
        end: endAt ? formatDateTime(endAt) : t('publishing.notSet'),
      })}
    </>
  )
}

/** The regions' names, joined. */
export function useRegionsText() {
  const { t } = useTranslation(SPONSOR)
  const label = useRegionLabel()
  return (regions: readonly string[]) => (regions.length > 0 ? regions.map(label).join(', ') : t('publishing.notSet'))
}

/**
 * The problems a draft would be refused for, or a refusal's, each with what it is about: the server's message names the
 * problem, the page names the field (the server's "required" says no more than that).
 */
export function LabelledProblems({ problems, testId }: {
  problems: readonly Pick<Problem, 'field' | 'message'>[]
  testId?: string
}) {
  const { t } = useTranslation(SPONSOR)
  if (problems.length === 0) return null
  return (
    <ul className="border-destructive/50 text-destructive list-disc space-y-1 rounded-lg border py-3 pr-4 pl-8 text-sm"
      data-testid={testId}>
      {problems.map((problem, i) => {
        const place = problemPlace(problem.field)
        return (
          <li key={i} data-testid="problem" data-field={problem.field ?? ''}>
            {place && <span className="font-medium">{t(`publishing.field.${place}`)}: </span>}
            {problem.message}
          </li>
        )
      })}
    </ul>
  )
}

/** The quiz version a publication uses, by its quiz's versions (none once the quiz is removed). */
export function useVersionOf(quizId: string | null, versionId: string | null) {
  const versions = useQuery({
    queryKey: versionsQuery(quizId ?? ''),
    queryFn: () => readVersions(quizId!),
    enabled: !!quizId,
  })
  return { versions, version: versions.data?.find((v) => v.versionId === versionId) ?? null }
}

export interface SummaryValues {
  name: string
  /** Q64: the publication's own cover; null when the quiz's is shown; undefined when not known (not shown). */
  cover?: string | null
  /** Q47: the matching conditions of a public publication; undefined while unknown (not shown). */
  conditions?: readonly Condition[]
  /** Q76: what a taker must have done first; undefined while unknown (not shown). */
  prerequisites?: readonly PrerequisiteRow[]
  quizTitle: string | null
  versionLabel: string | null
  regions: readonly string[]
  startAt: string | null
  endAt: string | null
  timeLimitSec: number | null
  passScore: number | null
  fullScore: number | null
  budgetCap: string | null
  maxWinners: number | null
  maxReward: string | null
  maxSpend: string | null
  tiers: readonly Pick<Tier, 'fromScore' | 'toScore' | 'reward'>[]
  /** Q22: how the reward reaches the taker, and the prize when it is collected at the sponsor's place. */
  rewardMethod?: RewardMethod
  redeemDescription?: string | null
  /** Q33: a digital prize's delivery, what the winner gets, and the days a sponsor has to send one by hand. */
  digitalDelivery?: DigitalDelivery | null
  digitalDescription?: string | null
  digitalSendDays?: number | null
  /** Q25: who may answer; an invited publication shows no regions. */
  audience?: Audience
  /** Q70: "referrals welcome"; none when not. */
  referral?: ReferralPolicy | null
}

/** What a publication is: its version, audience, time, marks and money, and its tiers. Amounts in Kudos. */
/** Q76: the prerequisites in a line: "Level 1 (passed) · Survey (answered)"; none is nothing to do first. */
export function usePrerequisitesText(): (prerequisites: readonly PrerequisiteRow[]) => string {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  return (prerequisites) => prerequisites.length === 0 ? t('publishing.prerequisites.none') : prerequisites
    .map((p) => t('publishing.prerequisites.item', { name: p.name,
      requirement: tQb(`prerequisite.${p.requirement.toLowerCase()}`) })).join(' · ')
}

/** The conditions in a line each criterion: "Gender: Female, Other · Age: 20–39 years"; none is everybody. */
export function useConditionsText(): (conditions: readonly Condition[]) => string {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  return (conditions) => conditions.length === 0 ? t('publishing.audience.everybody') : conditions.map((condition) => {
    const label = tQb(`audience.${condition.key}`)
    const shown = criterionOf(condition.key)?.kind === 'RANGE'
      ? tQb('audience.years', { min: condition.min, max: condition.max })
      : (condition.options ?? []).map((option) => tQb(`audience.${condition.key}Options.${option}`)).join(', ')
    return `${label}: ${shown}`
  }).join(' · ')
}

export function PublicationSummary({ values }: { values: SummaryValues }) {
  const { t } = useTranslation(SPONSOR)
  const regionsText = useRegionsText()
  const tiersId = useId()
  const notSet = t('publishing.notSet')
  const noLimit = t('publishing.noLimit')
  const methodLabel = useRewardMethodLabel()
  const audienceLabel = useAudienceLabel()
  const deliveryLabel = useDigitalDeliveryLabel()
  const conditionsText = useConditionsText()
  const prerequisitesText = usePrerequisitesText()
  const method = values.rewardMethod ?? 'PLATFORM'
  const delivery = values.digitalDelivery ?? null
  const audience = values.audience ?? 'PUBLIC'
  const items: DescriptionItem[] = [
    { key: 'name', label: t('publishing.field.name'), value: values.name || notSet },
    ...(values.cover === undefined ? [] : [{
      key: 'cover',
      label: t('publishing.field.cover'),
      value: values.cover
        ? <span data-testid="summary-cover"><Thumbnail fileId={values.cover} width={160} className="h-16 w-28 rounded-md" /></span>
        : <span data-testid="summary-cover-quiz">{t('publishing.summary.coverOfQuiz')}</span>,
    }]),
    {
      key: 'version',
      label: t('publishing.summary.quiz'),
      value: values.versionLabel
        ? t('publishing.summary.quizValue', { title: values.quizTitle ?? '', label: values.versionLabel })
        : notSet,
    },
    { key: 'audience', label: t('publishing.field.audience'),
      value: <span data-testid="summary-audience">{audienceLabel(audience)}</span> },
    ...(audience === 'PUBLIC'
      ? [{ key: 'regions', label: t('publishing.field.regions'), value: regionsText(values.regions) }] : []),
    ...(audience === 'PUBLIC' && values.conditions
      ? [{ key: 'conditions', label: t('publishing.audience.title'),
        value: <span data-testid="summary-conditions">{conditionsText(values.conditions)}</span> }] : []),
    ...(values.prerequisites
      ? [{ key: 'prerequisites', label: t('publishing.prerequisites.title'),
        value: <span data-testid="summary-prerequisites">{prerequisitesText(values.prerequisites)}</span> }] : []),
    { key: 'period', label: t('publishing.list.period'), value: <Period startAt={values.startAt} endAt={values.endAt} /> },
    {
      key: 'timeLimit',
      label: t('publishing.field.timeLimitSec'),
      value: values.timeLimitSec === null ? notSet : t('publishing.summary.timeLimit', { seconds: values.timeLimitSec }),
    },
    { key: 'passScore', label: t('publishing.field.passScore'), value: values.passScore ?? notSet },
    { key: 'fullScore', label: t('publishing.summary.fullScore'), value: values.fullScore ?? notSet },
    { key: 'budgetCap', label: t('publishing.field.budgetCap'),
      value: values.budgetCap === null ? noLimit : formatKudos(values.budgetCap) },
    { key: 'maxWinners', label: t('publishing.field.maxWinners'), value: values.maxWinners ?? noLimit },
    { key: 'rewardMethod', label: t('publishing.field.rewardMethod'),
      value: <span data-testid="summary-reward-method">{methodLabel(method)}</span> },
    ...(method === 'OFFLINE_REDEEM' ? [{ key: 'redeemDescription', label: t('publishing.field.redeemDescription'),
      value: <span className="whitespace-pre-line" data-testid="summary-redeem">{values.redeemDescription || notSet}</span> }] : []),
    ...(method === 'DIGITAL_PRIZE' ? [
      { key: 'digitalDelivery', label: t('publishing.field.digitalDelivery'),
        value: <span data-testid="summary-digital-delivery">{delivery ? deliveryLabel(delivery) : notSet}</span> },
      { key: 'digitalDescription', label: t('publishing.field.digitalDescription'),
        value: <span className="whitespace-pre-line" data-testid="summary-digital">{values.digitalDescription || notSet}</span> },
      ...(delivery === 'SPONSOR_SENT' ? [{ key: 'digitalSendDays', label: t('publishing.field.digitalSendDays'),
        value: <span data-testid="summary-digital-send-days">{values.digitalSendDays == null
          ? t('publishing.field.digitalSendDaysDefault') : t('publishing.summary.days', { days: values.digitalSendDays })}</span> }] : []),
    ] : []),
    ...(values.referral !== undefined ? [{ key: 'referral', label: t('publishing.referral.title'),
      value: <span data-testid="summary-referral">{values.referral
        ? t('publishing.summary.referral', { quota: values.referral.quota, amount: formatKudos(values.referral.amount),
          method: methodLabel(values.referral.method), days: values.referral.days,
          rules: values.referral.refereeRules.length === 0 ? t('publishing.summary.referralAnybody')
            : values.referral.refereeRules.map((rule) => t(`publishing.referral.rule.${rule}`)).join(' · ') })
        : t('publishing.summary.referralOff')}</span> }] : []),
    { key: 'maxReward', label: t('publishing.summary.maxReward'),
      value: values.maxReward === null ? notSet : formatKudos(values.maxReward) },
    { key: 'maxSpend', label: t('publishing.summary.maxSpend'),
      value: <span data-testid="summary-max-spend">{values.maxSpend === null ? noLimit : formatKudos(values.maxSpend)}</span> },
  ]
  return (
    <div className="flex flex-col gap-4" data-testid="publication-summary">
      <DescriptionList items={items} columns={2} />
      <div className="flex flex-col gap-2">
        <h3 className="text-sm font-medium" id={tiersId}>{t('publishing.summary.tiersTable')}</h3>
        {values.tiers.length === 0
          ? <p className="text-muted-foreground text-sm">{t('publishing.tiers.none')}</p>
          : (
            <Table aria-labelledby={tiersId} data-testid={tiersId}>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">#</TableHead>
                  <TableHead scope="col">{t('publishing.summary.scores')}</TableHead>
                  <TableHead scope="col" className="text-right">{t('publishing.tiers.reward')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {values.tiers.map((tier, i) => (
                  <TableRow key={i} data-testid="summary-tier">
                    <TableCell>{i + 1}</TableCell>
                    <TableCell>{t('publishing.tiers.range', { from: tier.fromScore, to: tier.toScore })}</TableCell>
                    <TableCell className="text-right">{formatKudos(tier.reward)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
      </div>
    </div>
  )
}
