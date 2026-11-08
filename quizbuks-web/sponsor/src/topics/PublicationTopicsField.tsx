import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import type { VersionRow } from '../content/api'
import { SPONSOR } from '../messages'
import { sameWord, selectable, topicName, TOPIC_LIMITS } from '../qb/topics'
import type { TopicChanges } from '../publishing/api'
import { AddTopics, TopicChip, useTopicList } from './TopicParts'

/** A publication's topics as its changes make them of its version's (backend `TopicRules.effective`). */
export function effectiveTopics(version: Pick<VersionRow, 'topics' | 'customTopics'> | null | undefined,
  changes: TopicChanges): { codes: string[]; custom: string[] } {
  const codes = (version?.topics ?? []).filter((code) => !changes.remove.includes(code))
  changes.add.forEach((code) => { if (!codes.includes(code)) codes.push(code) })
  const custom = (version?.customTopics ?? []).filter((word) => !changes.customRemove.some((each) => sameWord(each, word)))
  changes.customAdd.forEach((word) => { if (!custom.some((each) => sameWord(each, word))) custom.push(word) })
  return { codes, custom }
}

/**
 * A draft publication's topics (F-S9) in the wizard's first step: its quiz version's (each can be left out, and used
 * again) and its own additions, words of the list or custom words. The page keeps the changes and the wizard saves them
 * with the draft (`QB_PUBLICATION_TOPICS_SET`, after the draft's own save); submitted, they are frozen.
 */
export function PublicationTopicsField({ version, changes, onChange, disabled }: {
  version: VersionRow | null | undefined
  changes: TopicChanges
  onChange: (changes: TopicChanges) => void
  disabled: boolean
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const { words, offered, language } = useTopicList()
  const max = TOPIC_LIMITS.maxPerQuiz
  const fromVersion = version?.topics ?? []
  const customFromVersion = version?.customTopics ?? []
  const effective = effectiveTopics(version, changes)
  const full = effective.codes.length + effective.custom.length >= max
  const note = (code: string) => (words.size > 0 && !selectable(words.get(code)) ? [t('topics.noLonger')] : [])
  const toggle = (list: string[], value: string) =>
    list.includes(value) ? list.filter((each) => each !== value) : [...list, value]
  return (
    <fieldset className="flex flex-col gap-3 rounded-lg border p-4" data-testid="publication-topics">
      <legend className="px-1 text-sm font-medium">{t('topics.title')}</legend>
      <p className="text-muted-foreground text-sm">{t('topics.publicationHint', { max })}</p>
      <p className="text-muted-foreground text-xs" data-testid="publication-topics-count">
        {t('topics.count', { count: effective.codes.length + effective.custom.length, max })}</p>
      <ul className="flex flex-wrap gap-2" aria-label={t('topics.title')}>
        {fromVersion.map((code) => {
          const name = topicName(code, words, language)
          const left = changes.remove.includes(code)
          return (
            <TopicChip key={`v:${code}`} label={name} crossed={left} testId={`publication-topic-${code}`}
              notes={[left ? t('topics.left') : t('topics.fromVersion'), ...note(code)]}
              action={left ? 'restore' : 'remove'} disabled={disabled}
              actionLabel={left ? t('topics.restore', { name }) : t('topics.leaveOut', { name })}
              onAction={() => onChange({ ...changes, remove: toggle(changes.remove, code) })} />
          )
        })}
        {customFromVersion.map((word, index) => {
          const left = changes.customRemove.some((each) => sameWord(each, word))
          return (
            <TopicChip key={`vc:${word}`} label={word} crossed={left} testId={`publication-version-custom-${index}`}
              notes={[t('topics.own'), left ? t('topics.left') : t('topics.fromVersion')]}
              action={left ? 'restore' : 'remove'} disabled={disabled}
              actionLabel={left ? t('topics.restore', { name: word }) : t('topics.leaveOut', { name: word })}
              onAction={() => onChange({ ...changes, customRemove: left
                ? changes.customRemove.filter((each) => !sameWord(each, word)) : [...changes.customRemove, word] })} />
          )
        })}
        {changes.add.filter((code) => !fromVersion.includes(code)).map((code) => {
          const name = topicName(code, words, language)
          return (
            <TopicChip key={`a:${code}`} label={name} notes={note(code)} testId={`publication-topic-${code}`}
              action="remove" actionLabel={t('topics.remove', { name })} disabled={disabled}
              onAction={() => onChange({ ...changes, add: changes.add.filter((each) => each !== code) })} />
          )
        })}
        {changes.customAdd.map((word, index) => (
          <TopicChip key={`ac:${word}`} label={word} notes={[t('topics.own')]} testId={`publication-custom-${index}`}
            action="remove" actionLabel={t('topics.remove', { name: word })} disabled={disabled}
            onAction={() => onChange({ ...changes, customAdd: changes.customAdd.filter((each) => each !== word) })} />
        ))}
      </ul>
      <AddTopics id={id} testId="publication-topics-add" words={words} language={language} full={full}
        disabled={disabled} choices={offered.filter((topic) => !effective.codes.includes(topic.topicId))}
        onWord={(code) => onChange(fromVersion.includes(code)
          ? { ...changes, remove: changes.remove.filter((each) => each !== code) }
          : { ...changes, add: [...changes.add, code] })}
        onCustom={(word) => {
          if (effective.custom.some((each) => sameWord(each, word))) return
          const removed = changes.customRemove.find((each) => sameWord(each, word))
          onChange(removed !== undefined
            ? { ...changes, customRemove: changes.customRemove.filter((each) => each !== removed) }
            : { ...changes, customAdd: [...changes.customAdd, word] })
        }} />
    </fieldset>
  )
}
