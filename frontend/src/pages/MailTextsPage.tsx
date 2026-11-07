import { ApiError, useAuth } from '@jabiz/client'
import { Badge, DataTable, PageState, type ColumnDef } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  APPROVAL_READ,
  fetchMailTexts,
  fetchPendingChanges,
  MAIL_TEXT_TARGET,
  textTargetKey,
  type MailTemplateTexts,
} from '../api/mail'
import StateBadge from '../components/mail/StateBadge'
import AdminPage from '../layout/AdminPage'
import { mailPaths } from './paths'

/**
 * "Mail templates" (docs/design/18-numbering-approvals-tasks.md section 5.6; decision D72; phase 17zh-c): every
 * template the application sends, with its category, whether changes need another administrator's approval and per
 * language what is sent now (the default, the customised text, a change waiting for approval, a customised text
 * that is not sent because it no longer passes the rules or lacks the approval it needs now, or one whose default
 * changed since). `GET /api/mail/texts` (`mail.text.read`); the pending changes are the proposed controlled changes.
 */
export default function MailTextsPage() {
  const { t } = useTranslation()
  const { can } = useAuth()
  const texts = useQuery({ queryKey: ['mail', 'texts'], queryFn: fetchMailTexts })
  const pending = useQuery({
    queryKey: ['mail', 'pending', MAIL_TEXT_TARGET],
    enabled: can(APPROVAL_READ),
    queryFn: () => fetchPendingChanges(MAIL_TEXT_TARGET),
  })
  const pendingKeys = new Set((pending.data ?? []).map((change) => change.targetKey))

  if (texts.error) {
    return (
      <PageState kind="error" titleAs="h1" title={texts.error instanceof ApiError ? texts.error.display : t('app.error')} />
    )
  }

  const columns: ColumnDef<MailTemplateTexts, unknown>[] = [
    {
      id: 'name',
      header: t('mail.texts.name'),
      cell: ({ row: { original: template } }) => (
        <span className="flex flex-col">
          <Link
            to={mailPaths.text(template.template ?? '')}
            className="font-medium underline-offset-4 hover:underline"
            data-testid={`mail-template-${template.template}`}
          >
            {template.name ?? template.template}
          </Link>
          <code className="text-muted-foreground font-mono text-xs">{template.template}</code>
        </span>
      ),
      meta: { card: 'title' },
    },
    {
      id: 'category',
      header: t('mail.texts.category'),
      cell: ({ row }) => t(`mail.category.${row.original.category ?? 'TRANSACTIONAL'}`, { defaultValue: row.original.category }),
      meta: { card: 'subtitle' },
    },
    {
      id: 'approval',
      header: t('mail.texts.approval'),
      cell: ({ row }) =>
        row.original.needsApproval ? (
          <Badge variant="warning" data-testid="mail-needs-approval">{t('mail.texts.needsApproval')}</Badge>
        ) : (
          <span className="text-muted-foreground">{t('mail.texts.immediate')}</span>
        ),
    },
    {
      id: 'languages',
      header: t('mail.texts.languages'),
      cell: ({ row: { original: template } }) => (
        <span className="flex flex-wrap gap-1">
          {(template.languages ?? []).map((language) => {
            const locale = language.locale ?? ''
            const state = pendingKeys.has(textTargetKey(template.template ?? '', locale))
              ? 'PENDING' : (language.state ?? 'DEFAULT')
            return <StateBadge key={locale} locale={locale} state={state} />
          })}
        </span>
      ),
    },
  ]

  return (
    <AdminPage title={t('mail.texts.title')} description={t('mail.texts.hint')}>
      <DataTable
        label={t('mail.texts.title')}
        columns={columns}
        data={texts.data?.templates ?? []}
        getRowId={(template) => template.template ?? ''}
        loading={texts.isLoading}
        empty={t('mail.texts.empty')}
        storageKey="jabiz.mail.texts.view"
        rowProps={(template) => ({ 'data-testid': `mail-row-${template.template}` })}
      />
    </AdminPage>
  )
}
