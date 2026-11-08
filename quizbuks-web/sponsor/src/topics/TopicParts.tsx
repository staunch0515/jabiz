import { Badge, Button, Combobox, Input, Label } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { RotateCcw, X } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { TEMPLATE } from '../qb/ids'
import { byCode, normalizedWord, offered, readTopics, TOPIC_LIMITS, type Topic } from '../qb/topics'
import './messages'

/** The query of the word list as the sponsor console reads it. */
export const TOPICS_QUERY = ['qb', 'topics', 'sponsor'] as const

/** The word list: every word, by code, and those offered in the console's language. */
export function useTopicList() {
  const { i18n } = useTranslation(SPONSOR)
  const list = useQuery({ queryKey: TOPICS_QUERY, queryFn: () => readTopics(TEMPLATE.sponsorTopics),
    staleTime: 300_000 })
  return { list, words: byCode(list.data ?? []), offered: offered(list.data ?? [], i18n.language),
    language: i18n.language }
}

/**
 * One topic as a chip: its name, a note (a custom word, from the version, no longer offered) and a button to remove
 * it, or, for a word left out of a publication, to use it again.
 */
export function TopicChip({ label, notes, action, actionLabel, onAction, disabled, crossed, testId }: {
  label: string
  notes: string[]
  action: 'remove' | 'restore'
  actionLabel: string
  onAction: () => void
  disabled: boolean
  crossed?: boolean
  testId: string
}) {
  return (
    <li className="flex items-center gap-1" data-testid={testId}>
      <Badge variant={crossed ? 'outline' : 'secondary'} className="gap-1 py-1 pr-1">
        <span className={crossed ? 'line-through' : undefined}>{label}</span>
        {notes.map((note) => <span key={note} className="text-muted-foreground text-xs">{note}</span>)}
        <Button type="button" variant="ghost" size="icon-sm" className="size-6" aria-label={actionLabel}
          disabled={disabled} onClick={onAction} data-testid={`${testId}-${action}`}>
          {action === 'remove' ? <X aria-hidden className="size-3.5" /> : <RotateCcw aria-hidden className="size-3.5" />}
        </Button>
      </Badge>
    </li>
  )
}

/**
 * Adding a topic: a word of the list (offered ones not chosen yet, searched by name) or a word of the sponsor's own
 * (normalized as the server does; refused there when too long). Nothing is offered once the topics are full.
 */
export function AddTopics({ id, choices, words, language, full, disabled, onWord, onCustom, testId }: {
  id: string
  choices: Topic[]
  words: ReadonlyMap<string, Topic>
  language: string
  full: boolean
  disabled: boolean
  onWord: (code: string) => void
  onCustom: (word: string) => void
  testId: string
}) {
  const { t } = useTranslation(SPONSOR)
  const [custom, setCustom] = useState('')
  const max = TOPIC_LIMITS.customMaxLength
  const word = normalizedWord(custom)
  const addCustom = () => {
    if (word === '' || full) return
    onCustom(word)
    setCustom('')
  }
  const name = (topic: Topic) => topic.names[language.split('-')[0]] || topic.names.en || topic.topicId
  return (
    <div className="flex flex-col gap-3" data-testid={testId}>
      {full && <p className="text-muted-foreground text-sm" data-testid={`${testId}-full`}>{t('topics.full')}</p>}
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${id}-word`}>{t('topics.add')}</Label>
        <Combobox id={`${id}-word`} value={null} disabled={disabled || full}
          onChange={(code) => { if (code && words.has(code)) onWord(code) }}
          options={choices.map((topic) => ({ value: topic.topicId, label: name(topic) }))}
          placeholder={t('topics.addPlaceholder')} searchPlaceholder={t('topics.search')}
          emptyText={t('topics.noMatch')} className="w-full max-w-md" data-testid={`${testId}-word`} />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${id}-custom`}>{t('topics.custom')}</Label>
        <div className="flex max-w-md gap-2">
          <Input id={`${id}-custom`} value={custom} maxLength={max} autoComplete="off" disabled={disabled || full}
            aria-describedby={`${id}-custom-hint`} data-testid={`${testId}-custom`}
            onChange={(event) => setCustom(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                addCustom()
              }
            }} />
          <Button type="button" variant="outline" disabled={disabled || full || word === ''} onClick={addCustom}
            data-testid={`${testId}-custom-add`}>{t('topics.customAdd')}</Button>
        </div>
        <p id={`${id}-custom-hint`} className="text-muted-foreground text-sm">{t('topics.customHint', { max })}</p>
      </div>
    </div>
  )
}
