import { i18n } from '@jabiz/client'
import { Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { pickText } from '../qb/legal'
import { Link } from '../qb/router'
import { readBindableNodes, type BindableNode } from '../qb/tree/api'
import { TREE_PATH } from '../tree/api'
import '../tree/messages'

const NONE = 'none'
const valueOf = (node: { treeId: string; nodeId: string }) => `${node.treeId}/${node.nodeId}`

/**
 * F-S3d (F-S3 §5): the wizard's first field, which knowledge map node the publication lights (or none). The choices
 * are `qb.sponsor.tree-bindable-nodes` (`publishing.PublicationNodes`): the Sponsor's own map's nodes asking for a pass,
 * and the platform's for an operator's direct Sponsor only. A saved node that is no longer a choice still shows, so
 * that the server's problem about it reads against it.
 */
export function NodeField({ id, value, onChange, disabled, problem }: {
  id: string
  value: { treeId: string; nodeId: string } | null
  onChange: (node: { treeId: string; nodeId: string } | null) => void
  disabled?: boolean
  problem?: string
}) {
  const { t } = useTranslation(SPONSOR)
  const nodes = useQuery({ queryKey: ['qb', 'tree-bindable-nodes'], queryFn: readBindableNodes })
  const choices = nodes.data ?? []
  const label = (node: BindableNode) => `${pickText(node.name, i18n.language).text} · ${
    pickText(node.treeName, i18n.language).text}${node.ownerKind === 'PLATFORM' ? ` (${t('tree.bind.platform')})` : ''}`
  const current = value ? valueOf(value) : NONE
  const known = !value || choices.some((node) => valueOf(node) === current)
  const describedBy = [`${id}-hint`, problem ? `${id}-error` : undefined].filter(Boolean).join(' ')
  return (
    <div className="flex flex-col gap-2" data-testid="publication-node-field">
      <Label htmlFor={id}>{t('tree.bind.label')}</Label>
      <Select value={current} disabled={disabled || nodes.isPending}
        onValueChange={(next) => {
          if (next === NONE) return onChange(null)
          const [treeId, nodeId] = next.split('/')
          onChange({ treeId, nodeId })
        }}>
        <SelectTrigger id={id} className="w-full max-w-md" aria-describedby={describedBy}
          aria-invalid={problem ? true : undefined} data-testid="publication-node">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={NONE}>{t('tree.bind.none')}</SelectItem>
          {choices.map((node) => (
            <SelectItem key={valueOf(node)} value={valueOf(node)}>{label(node)}</SelectItem>
          ))}
          {!known && value && <SelectItem value={current}>{t('tree.bind.unavailable')}</SelectItem>}
        </SelectContent>
      </Select>
      <p id={`${id}-hint`} className="text-muted-foreground text-sm">
        {nodes.isSuccess && choices.length === 0 ? (
          <>{t('tree.bind.noChoices')}{' '}
            <Link to={TREE_PATH} className="text-primary underline underline-offset-4">{t('tree.bind.toMap')}</Link></>
        ) : t('tree.bind.hint')}
      </p>
      {problem && <p id={`${id}-error`} className="text-destructive text-sm">{problem}</p>}
    </div>
  )
}
