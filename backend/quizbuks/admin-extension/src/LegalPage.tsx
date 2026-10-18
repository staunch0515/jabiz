import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Ban, Send } from 'lucide-react'
import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  Badge,
  Button,
  Checkbox,
  ConfirmDialog,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  MarkdownView,
  notify,
  PageHeader,
  paths,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  UserName,
} from '@jabiz/admin'
import { LoadFailed, Section } from './components'
import { failed } from './finance'
import { when } from './hooks'
import { DATASET } from './ids'
import {
  LEGAL_KINDS,
  publishLegal,
  readLegalDrafts,
  readLegalVersions,
  withdrawLegal,
  type LegalDraft,
  type LegalVersionRow,
} from './legal-api'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const DRAFTS_KEY = ['qb-ext', 'legal', 'drafts']
const VERSIONS_KEY = ['qb-ext', 'legal', 'versions']
const REASON_LENGTH = 1000

/** A text in the reader's language, else English, else Japanese. */
function pick(text: Record<string, string>, language: string): string {
  return text[language.split('-')[0]] || text.en || text.ja || ''
}

type State = 'withdrawn' | 'announced' | 'inEffect' | 'replaced'

/** Each version's state as this page sees it (the server decides with its own clock when publishing or withdrawing). */
function statesOf(rows: LegalVersionRow[], now: number): Map<string, State> {
  const states = new Map<string, State>()
  const standing = rows.filter((row) => !row.withdrawn)
  const inEffect = standing.filter((row) => Date.parse(row.effectiveAt) <= now)
    .reduce((top, row) => Math.max(top, row.versionNo), 0)
  for (const row of rows) {
    if (row.withdrawn) states.set(row.legalVersionId, 'withdrawn')
    else if (Date.parse(row.effectiveAt) > now) states.set(row.legalVersionId, 'announced')
    else states.set(row.legalVersionId, row.versionNo === inEffect ? 'inEffect' : 'replaced')
  }
  return states
}

/**
 * Publishes the kind's draft as read (its version goes with it, review L1): now, or from a later time (announced
 * until then); with a second factor, which the platform asks for.
 */
function PublishForm({ draft, kindLabel }: { draft: LegalDraft; kindLabel: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const queryClient = useQueryClient()
  const id = useId()
  const [effectiveAt, setEffectiveAt] = useState('')
  const [requiresConsent, setRequiresConsent] = useState(false)
  const [announce, setAnnounce] = useState(false)
  const first = draft.latestVersionNo == null
  return (
    <div className="flex flex-col gap-3" data-testid={`legal-publish-${draft.kind}`}>
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-at`}>{t('legal.effectiveAt')}</Label>
          <Input id={`${id}-at`} type="datetime-local" className="w-56" value={effectiveAt}
            onChange={(event) => setEffectiveAt(event.target.value)} data-testid="legal-effective-at" />
        </div>
        <div className="flex items-center gap-2 pb-2">
          <Checkbox id={`${id}-consent`} checked={first || requiresConsent} disabled={first}
            onCheckedChange={(checked) => setRequiresConsent(checked === true)} data-testid="legal-requires-consent" />
          <Label htmlFor={`${id}-consent`}>{t('legal.requiresConsent')}</Label>
        </div>
        <div className="flex items-center gap-2 pb-2">
          <Checkbox id={`${id}-announce`} checked={announce}
            onCheckedChange={(checked) => setAnnounce(checked === true)} data-testid="legal-announce" />
          <Label htmlFor={`${id}-announce`}>{t('legal.announce')}</Label>
        </div>
      </div>
      <p className="text-muted-foreground text-sm">{t('legal.publishHint')}</p>
      <div>
        <ConfirmDialog
          title={t('legal.publishTitle', { kind: kindLabel })}
          description={effectiveAt ? t('legal.publishLater', { time: when(new Date(effectiveAt).toISOString()) })
            : t('legal.publishNow')}
          confirmLabel={t('legal.publish')}
          onConfirm={async () => {
            try {
              const published = await publishLegal(draft, effectiveAt ? new Date(effectiveAt).toISOString() : null,
                first || requiresConsent, announce)
              notify.success(t('legal.published', { n: published.versionNo }))
              if (published.announcements > 0) notify.info(t('legal.announced'))
              setEffectiveAt('')
              setRequiresConsent(false)
              setAnnounce(false)
            } catch (error) {
              failed(error, t('common.failed'))
            } finally {
              await Promise.all([queryClient.invalidateQueries({ queryKey: DRAFTS_KEY }),
                queryClient.invalidateQueries({ queryKey: VERSIONS_KEY })])
            }
          }}
          trigger={(
            <Button data-testid="legal-publish">
              <Send aria-hidden />
              {t('legal.publish')}
            </Button>
          )}
        />
      </div>
    </div>
  )
}

/** Withdraws a version announced for later, with the reason (review M1); asks first. */
function WithdrawForm({ row }: { row: LegalVersionRow }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const queryClient = useQueryClient()
  const [reason, setReason] = useState('')
  return (
    <div className="flex items-center gap-2" data-testid="legal-withdraw-form">
      <Input className="h-8 w-48" value={reason} maxLength={REASON_LENGTH} placeholder={t('legal.reason')}
        aria-label={t('legal.reasonFor', { n: row.versionNo })} onChange={(event) => setReason(event.target.value)}
        data-testid="legal-withdraw-reason" />
      <ConfirmDialog
        destructive
        title={t('legal.withdrawTitle', { n: row.versionNo })}
        description={t('legal.withdrawDescription', { time: when(row.effectiveAt) })}
        confirmLabel={t('legal.withdraw')}
        onConfirm={async () => {
          try {
            await withdrawLegal(row.legalVersionId, reason.trim())
            notify.success(t('legal.withdrawn', { n: row.versionNo }))
          } catch (error) {
            failed(error, t('common.failed'))
          } finally {
            await queryClient.invalidateQueries({ queryKey: VERSIONS_KEY })
          }
        }}
        trigger={(
          <Button size="sm" variant="outline" disabled={!reason.trim()} data-testid="legal-withdraw">
            <Ban aria-hidden />
            {t('legal.withdraw')}
          </Button>
        )}
      />
    </div>
  )
}

function Versions({ rows, kindLabel, now }: { rows: LegalVersionRow[]; kindLabel: string; now: number }) {
  const { t, i18n: { language } } = useTranslation(EXTENSION_NAMESPACE)
  const states = statesOf(rows, now)
  if (rows.length === 0) {
    return <p className="text-muted-foreground text-sm">{t('legal.noVersions')}</p>
  }
  return (
    <Table aria-label={t('legal.versionsOf', { kind: kindLabel })}>
      <TableHeader>
        <TableRow>
          <TableHead>{t('legal.version')}</TableHead>
          <TableHead>{t('legal.state')}</TableHead>
          <TableHead>{t('legal.effective')}</TableHead>
          <TableHead>{t('legal.publishedBy')}</TableHead>
          <TableHead>{t('legal.consent')}</TableHead>
          <TableHead>{t('legal.actions')}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => {
          const state = states.get(row.legalVersionId)!
          return (
            <TableRow key={row.legalVersionId} data-testid="legal-version-row" data-version={row.versionNo}
              data-state={state}>
              <TableCell>
                <span className="flex flex-col">
                  <span className="font-medium">{t('legal.versionNo', { n: row.versionNo })}</span>
                  <span className="text-muted-foreground text-xs">{pick(row.title, language)}</span>
                </span>
              </TableCell>
              <TableCell>
                <Badge variant={state === 'inEffect' ? 'success' : state === 'announced' ? 'secondary' : 'outline'}>
                  {t(`legal.states.${state}`)}
                </Badge>
              </TableCell>
              <TableCell>{when(row.effectiveAt)}</TableCell>
              <TableCell>
                <span className="flex flex-col">
                  <span>{when(row.publishedAt)}</span>
                  {row.publishedBy && <UserName id={row.publishedBy} />}
                </span>
              </TableCell>
              <TableCell>{row.requiresConsent ? t('common.yes') : t('common.no')}</TableCell>
              <TableCell>
                {state === 'announced' && <WithdrawForm row={row} />}
                {row.withdrawn && (
                  <span className="flex flex-col text-sm" data-testid="legal-withdrawal">
                    <span>{when(row.withdrawnAt)}</span>
                    {row.withdrawnBy && <UserName id={row.withdrawnBy} />}
                    <span className="text-muted-foreground">{row.reason}</span>
                  </span>
                )}
              </TableCell>
            </TableRow>
          )
        })}
      </TableBody>
    </Table>
  )
}

/** The draft as the next version would be: its title and text in Japanese (which governs) and English. */
function DraftPreview({ draft }: { draft: LegalDraft }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <details className="rounded-md border p-3" data-testid="legal-draft">
      <summary className="cursor-pointer text-sm font-medium">
        {t('legal.draft', { revision: draft.revision })}
      </summary>
      <div className="mt-3 grid gap-4 md:grid-cols-2">
        {(['ja', 'en'] as const).map((language) => (
          <div key={language} className="flex flex-col gap-2" lang={language}>
            <p className="text-muted-foreground text-xs">{t(`legal.languages.${language}`)}</p>
            <p className="font-semibold">{draft.title[language] || t('legal.missing')}</p>
            <div className="text-sm"><MarkdownView text={draft.body[language] ?? ''} /></div>
            {draft.changeSummary[language] && (
              <div className="text-sm">
                <p className="font-medium">{t('legal.changeSummary')}</p>
                <MarkdownView text={draft.changeSummary[language]} />
              </div>
            )}
          </div>
        ))}
      </div>
    </details>
  )
}

/**
 * The legal texts (Q7b, D-Q7-4; review M1): for each kind its draft as the next version would be, publishing it (now
 * or from a later time) and its versions, where one announced for later can be withdrawn before it takes effect, with
 * the reason. The drafts are edited on the platform's generic pages; the server checks every publication and
 * withdrawal (permission, second factor, the draft's version, the dates).
 */
export default function LegalPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const drafts = useQuery({ queryKey: DRAFTS_KEY, queryFn: readLegalDrafts })
  const versions = useQuery({ queryKey: VERSIONS_KEY, queryFn: readLegalVersions })
  // The states shown are as of opening the page; the server decides with its own clock.
  const [now] = useState(() => Date.now())
  return (
    <div className="flex flex-col gap-4" data-testid="legal">
      <PageHeader title={t('legal.title')} description={t('legal.description')} className="pb-0"
        actions={<Link to={paths.dataset(DATASET.legalDoc)} className={linkClass}>{t('legal.editDrafts')}</Link>} />
      {drafts.isError || versions.isError
        ? <LoadFailed error={drafts.error ?? versions.error} onRetry={() => {
          void drafts.refetch()
          void versions.refetch()
        }} />
        : LEGAL_KINDS.map((kind) => {
          const draft = drafts.data?.find((d) => d.kind === kind)
          const kindLabel = t(`legal.kinds.${kind}`)
          return (
            <Section key={kind} title={kindLabel} testId={`legal-kind-${kind}`}>
              {draft && <DraftPreview draft={draft} />}
              {draft && <PublishForm draft={draft} kindLabel={kindLabel} />}
              {versions.data && (
                <Versions rows={versions.data.filter((row) => row.kind === kind)} kindLabel={kindLabel}
                  now={now} />
              )}
            </Section>
          )
        })}
    </div>
  )
}
