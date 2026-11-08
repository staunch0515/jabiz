import { Checkbox, Label, notify, Switch } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { DEFAULT_SETTINGS, saveMySettings, type UserSettings } from '../qb/api'
import { TEMPLATE } from '../qb/ids'
import { problemText } from '../qb/problems'
import { SETTINGS_QUERY, useSoundSetting } from '../qb/SoundSettings'
import { byCode, offered, readTopics, topicName } from '../qb/topics'

/** The query of the word list as the app reads it. */
export const TOPICS_QUERY = ['qb', 'topics'] as const

/**
 * A taker's liked topics and strict mode (F-S9), in the account's "about me": a checkbox a word of the platform's list
 * (in the reader's language), each change saved at once; a word liked before and no longer offered stays listed, ticked,
 * until unticked. Strict mode leaves the home and its shelf of short videos only the quizzes sharing a liked topic
 * (the server applies it: `qb.app.feed`); it needs a liked topic to turn on and turns off any time.
 */
export function LikedTopics() {
  const { t, i18n } = useTranslation(APP)
  const id = useId()
  const queryClient = useQueryClient()
  const settings = useSoundSetting()
  const topics = useQuery({ queryKey: TOPICS_QUERY, queryFn: () => readTopics(TEMPLATE.appTopics), staleTime: 300_000 })
  const liked = settings.data?.likedTopics ?? []
  const strict = settings.data?.strictTopics === true
  const words = byCode(topics.data ?? [])
  const choices = offered(topics.data ?? [], i18n.language)
  // Liked before and no longer offered (deactivated, or merged into another): shown until unticked.
  const kept = liked.filter((code) => !choices.some((topic) => topic.topicId === code))
  const save = useMutation({
    mutationFn: (change: Pick<UserSettings, 'likedTopics' | 'strictTopics'>) => saveMySettings(change),
    onMutate: (change) => {
      const before = queryClient.getQueryData<UserSettings>(SETTINGS_QUERY)
      queryClient.setQueryData<UserSettings>(SETTINGS_QUERY, { ...(before ?? DEFAULT_SETTINGS), ...change })
      return { before }
    },
    onError: (error, _change, saved) => {
      queryClient.setQueryData<UserSettings>(SETTINGS_QUERY, saved?.before ?? DEFAULT_SETTINGS)
      notify.error(problemText(error))
    },
    onSuccess: (saved) => queryClient.setQueryData<UserSettings>(SETTINGS_QUERY, saved),
    // The home and its shelf of shorts ask again (the server reads the settings itself).
    onSettled: () => Promise.all([queryClient.invalidateQueries({ queryKey: ['qb', 'feed'] }),
      queryClient.invalidateQueries({ queryKey: ['qb', 'feed-shorts'] })]),
  })
  const busy = settings.isPending || save.isPending
  const toggle = (code: string, on: boolean) => {
    const next = on ? [...liked, code] : liked.filter((each) => each !== code)
    // The last liked topic stays while strict mode is on (the server refuses it): turn strict mode off first.
    if (next.length === 0 && strict) {
      notify.error(t('topics.strictNeedsTopics'))
      return
    }
    save.mutate({ likedTopics: next })
  }
  const box = (code: string, on: boolean, disabled: boolean, note?: string) => (
    <li key={code} className="flex items-center gap-2">
      <Checkbox id={`${id}-${code}`} checked={on} disabled={busy || disabled} data-testid={`liked-topic-${code}`}
        onCheckedChange={(checked) => toggle(code, checked === true)} />
      <Label htmlFor={`${id}-${code}`} className="font-normal">
        {topicName(code, words, i18n.language)}
        {note && <>{' '}<span className="text-muted-foreground text-xs">{note}</span></>}
      </Label>
    </li>
  )
  return (
    <div className="flex flex-col gap-4" data-testid="liked-topics">
      <p className="text-muted-foreground text-sm">{t('topics.hint')}</p>
      {topics.isSuccess && choices.length === 0 && kept.length === 0 ? (
        <p className="text-muted-foreground text-sm" data-testid="liked-topics-none">{t('topics.none')}</p>
      ) : (
        <ul className="grid grid-cols-1 gap-2 sm:grid-cols-2" aria-label={t('topics.choose')}>
          {kept.map((code) => box(code, true, false, t('topics.noLonger')))}
          {choices.map((topic) => box(topic.topicId, liked.includes(topic.topicId), false))}
        </ul>
      )}
      <div className="flex flex-col gap-1" data-testid="strict-topics">
        <div className="flex items-center justify-between gap-3">
          <Label htmlFor={`${id}-strict`}>{t('topics.strict')}</Label>
          {/* On needs a liked topic; off always works. */}
          <Switch id={`${id}-strict`} checked={strict} data-testid="strict-topics-switch"
            disabled={busy || (!strict && liked.length === 0)} aria-describedby={`${id}-strict-hint`}
            onCheckedChange={(checked) => save.mutate({ strictTopics: checked })} />
        </div>
        <p id={`${id}-strict-hint`} className="text-muted-foreground text-sm">{t('topics.strictHint')}</p>
        {!strict && liked.length === 0 && (
          <p className="text-muted-foreground text-sm" data-testid="strict-topics-needs">
            {t('topics.strictNeedsTopics')}</p>
        )}
      </div>
    </div>
  )
}
