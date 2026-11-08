import { notify } from '@jabiz/ui'
import { useMutation } from '@tanstack/react-query'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { isStale, setQuizTopics, type Quiz } from '../content/api'
import { useEditing } from '../content/editing'
import { SPONSOR } from '../messages'
import { problemText } from '../qb/problems'
import { sameWord, selectable, topicName, TOPIC_LIMITS } from '../qb/topics'
import { AddTopics, TopicChip, useTopicList } from './TopicParts'

/**
 * A quiz's topics (F-S9) in its editor: the words of the platform's list it has (in the console's language; a word no
 * longer offered is marked and can only be removed) and the sponsor's own words, each change saved at once with the
 * quiz's revision (`QB_QUIZ_TOPICS_SET`). Up to `qb.topic.max-per-quiz` together; the next version keeps them.
 */
export function QuizTopicsField({ quiz }: { quiz: Quiz }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const editing = useEditing()
  const { words, offered, language } = useTopicList()
  const topics = quiz.topics ?? []
  const custom = quiz.customTopics ?? []
  const max = TOPIC_LIMITS.maxPerQuiz
  const full = topics.length + custom.length >= max
  const save = useMutation({
    mutationFn: ({ codes, own }: { codes: string[]; own: string[] }) =>
      setQuizTopics(editing.quizId, codes, own, editing.revision()),
    onSuccess: async (output) => {
      notify.success(t('topics.saved'))
      await editing.edited(output)
    },
    onError: editing.failed,
  })
  const busy = save.isPending || editing.stale
  return (
    <fieldset className="flex flex-col gap-3 rounded-lg border p-4" data-testid="quiz-topics">
      <legend className="px-1 text-sm font-medium">{t('topics.title')}</legend>
      <p className="text-muted-foreground text-sm">{t('topics.quizHint', { max })}</p>
      <p className="text-muted-foreground text-xs" data-testid="quiz-topics-count">
        {t('topics.count', { count: topics.length + custom.length, max })}</p>
      {topics.length + custom.length === 0 ? (
        <p className="text-muted-foreground text-sm">{t('topics.none')}</p>
      ) : (
        <ul className="flex flex-wrap gap-2" aria-label={t('topics.title')}>
          {topics.map((code) => {
            const name = topicName(code, words, language)
            return (
              <TopicChip key={`w:${code}`} label={name} testId={`quiz-topic-${code}`}
                notes={words.size > 0 && !selectable(words.get(code)) ? [t('topics.noLonger')] : []}
                action="remove" actionLabel={t('topics.remove', { name })} disabled={busy}
                onAction={() => save.mutate({ codes: topics.filter((each) => each !== code), own: custom })} />
            )
          })}
          {custom.map((word, index) => (
            <TopicChip key={`c:${word}`} label={word} notes={[t('topics.own')]} testId={`quiz-custom-${index}`}
              action="remove" actionLabel={t('topics.remove', { name: word })} disabled={busy}
              onAction={() => save.mutate({ codes: topics, own: custom.filter((each) => each !== word) })} />
          ))}
        </ul>
      )}
      {save.isError && !isStale(save.error) && (
        <p role="alert" className="text-destructive text-sm" data-testid="quiz-topics-problem">
          {problemText(save.error)}</p>
      )}
      <AddTopics id={id} testId="quiz-topics-add" words={words} language={language} full={full} disabled={busy}
        choices={offered.filter((topic) => !topics.includes(topic.topicId))}
        onWord={(code) => save.mutate({ codes: [...topics, code], own: custom })}
        onCustom={(word) => {
          if (!custom.some((each) => sameWord(each, word))) save.mutate({ codes: topics, own: [...custom, word] })
        }} />
    </fieldset>
  )
}
