import {
  Button, Checkbox, Input, Label, Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue,
} from '@jabiz/ui'
import { Plus, Trash2 } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import type { Condition } from '../qb/tree/states'
import {
  beforeAnswerAllowed, isGroup, outer, passAllowed, withBeforeAnswer, withItem, withItemAt, withN, withOp,
  withoutItem,
} from './draft'
import './messages'

type Group = Extract<Condition, { op: string }>
const OPS: Group['op'][] = ['ALL', 'ANY', 'AT_LEAST']

/**
 * A node's condition in the editor (F-S3d, F-S3 §5): the outermost group and one level of groups inside it, each item
 * another node of the map, a node of a platform map Takers light (F-S3e), or a pass of a publication bound to this node.
 * Only what `tree.TreeRules` accepts in shape is offered (`draft.ts`); a condition saved with deeper groups keeps them.
 */
/** A node of another map the condition may name, by its id, with its map's name. */
export interface ForeignNode {
  key: string
  name: string
  mapName: string
}

export function ConditionEditor({ condition, onChange, others, foreign = [], nameOf, disabled }: {
  condition: Condition
  onChange: (condition: Condition) => void
  /** The other nodes of the map, by key, to name. */
  others: { key: string; name: string }[]
  /** F-S3e: the nodes of the platform's maps Takers light (`qb.sponsor.tree-referenceable-nodes`). */
  foreign?: ForeignNode[]
  /** The name of a node by key or id (another map's included), for what the editor cannot pick. */
  nameOf: (node: string) => string
  disabled?: boolean
}) {
  return <GroupEditor group={outer(condition)} whole={condition} outermost others={others} foreign={foreign}
    nameOf={nameOf} disabled={disabled} onChange={onChange} />
}

function GroupEditor({ group, whole, outermost, others, foreign, nameOf, disabled, onChange }: {
  group: Group
  whole: Condition
  outermost: boolean
  others: { key: string; name: string }[]
  foreign: ForeignNode[]
  nameOf: (node: string) => string
  disabled?: boolean
  onChange: (group: Group) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const allowBefore = beforeAnswerAllowed(group, outermost)
  const named = new Set(group.items.flatMap((item) => (!isGroup(item) && 'node' in item ? [item.node] : [])))
  const free = others.filter((node) => !named.has(node.key))
  const freeForeign = foreign.filter((node) => !named.has(node.key))
  const maps = [...new Set(freeForeign.map((node) => node.mapName))]
  return (
    <fieldset className="flex flex-col gap-2 rounded-md border p-3" data-testid={outermost ? 'condition' : 'condition-group'}>
      <legend className="px-1 text-sm font-medium">{outermost ? t('tree.edit.condition') : t('tree.edit.group')}</legend>
      <div className="flex flex-wrap items-center gap-2">
        <Label htmlFor={`${id}-op`} className="sr-only">{t('tree.edit.op')}</Label>
        <Select value={group.op} disabled={disabled} onValueChange={(op) => onChange(withOp(group, op as Group['op'], outermost))}>
          <SelectTrigger id={`${id}-op`} className="w-48" data-testid="condition-op"><SelectValue /></SelectTrigger>
          <SelectContent>
            {OPS.map((op) => <SelectItem key={op} value={op}>{t(`tree.edit.ops.${op}`)}</SelectItem>)}
          </SelectContent>
        </Select>
        {group.op === 'AT_LEAST' && (
          <>
            <Label htmlFor={`${id}-n`} className="text-sm">{t('tree.edit.n')}</Label>
            <Input id={`${id}-n`} type="number" inputMode="numeric" min={1} max={Math.max(1, group.items.length)}
              className="w-20" value={group.n} disabled={disabled} data-testid="condition-n"
              onChange={(event) => onChange(withN(group, Number(event.target.value)))} />
          </>
        )}
      </div>
      {group.items.length === 0 && <p className="text-muted-foreground text-sm">{t('tree.edit.emptyGroup')}</p>}
      <ul className="flex flex-col gap-2">
        {group.items.map((item, index) => (
          <li key={index} className="flex flex-wrap items-center gap-2" data-testid="condition-item">
            {isGroup(item)
              ? (outermost
                ? <div className="w-full"><GroupEditor group={item} whole={whole} outermost={false} others={others}
                  foreign={foreign} nameOf={nameOf} disabled={disabled}
                  onChange={(next) => onChange(withItemAt(group, index, next))} /></div>
                : <span className="text-sm">{t('tree.edit.deeper')}</span>)
              : 'node' in item ? (
                <>
                  <span className="text-sm">{t('tree.edit.nodeItem', { name: nameOf(item.node) })}</span>
                  {allowBefore && (
                    <span className="flex items-center gap-1.5">
                      <Checkbox id={`${id}-before-${index}`} checked={item.beforeAnswer === true} disabled={disabled}
                        data-testid="condition-before-answer"
                        onCheckedChange={(on) => onChange(withItemAt(group, index, withBeforeAnswer(item, on === true, true)))} />
                      <Label htmlFor={`${id}-before-${index}`} className="text-sm font-normal">{t('tree.edit.beforeAnswer')}</Label>
                    </span>
                  )}
                </>
              ) : <span className="text-sm">{t('tree.edit.passItem')}</span>}
            <Button type="button" variant="ghost" size="icon" disabled={disabled}
              aria-label={t('tree.edit.removeItem')} data-testid="condition-remove"
              onClick={() => onChange(withoutItem(group, index))}>
              <Trash2 aria-hidden className="size-4" />
            </Button>
          </li>
        ))}
      </ul>
      <div className="flex flex-wrap items-center gap-2">
        {free.length + freeForeign.length > 0 && (
          <Select value="" disabled={disabled}
            onValueChange={(node) => onChange(withItem(group, { node }, whole))}>
            <SelectTrigger className="w-56" aria-label={t('tree.edit.addNode')} data-testid="condition-add-node">
              <SelectValue placeholder={t('tree.edit.addNode')} />
            </SelectTrigger>
            <SelectContent>
              {free.length > 0 && (
                <SelectGroup>
                  {freeForeign.length > 0 && <SelectLabel>{t('tree.edit.thisMap')}</SelectLabel>}
                  {free.map((node) => <SelectItem key={node.key} value={node.key}>{node.name}</SelectItem>)}
                </SelectGroup>
              )}
              {maps.map((map) => (
                <SelectGroup key={map}>
                  <SelectLabel>{t('tree.edit.platformMap', { name: map })}</SelectLabel>
                  {freeForeign.filter((node) => node.mapName === map).map((node) => (
                    <SelectItem key={node.key} value={node.key} data-testid="condition-platform-node">{node.name}</SelectItem>
                  ))}
                </SelectGroup>
              ))}
            </SelectContent>
          </Select>
        )}
        {passAllowed(whole) && (
          <Button type="button" variant="outline" size="sm" disabled={disabled} data-testid="condition-add-pass"
            onClick={() => onChange(withItem(group, { passed: true }, whole))}>
            <Plus aria-hidden className="size-4" />{t('tree.edit.addPass')}
          </Button>
        )}
        {outermost && (
          <Button type="button" variant="outline" size="sm" disabled={disabled} data-testid="condition-add-group"
            onClick={() => onChange(withItem(group, { op: 'ANY', items: [] }, whole))}>
            <Plus aria-hidden className="size-4" />{t('tree.edit.addGroup')}
          </Button>
        )}
      </div>
    </fieldset>
  )
}
