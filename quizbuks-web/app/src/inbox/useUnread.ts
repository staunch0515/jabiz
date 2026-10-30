import { i18n } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { shownLanguages } from '../qb/api'
import { useSoundSetting } from '../qb/SoundSettings'
import { readUnread, UNREAD_INTERVAL, UNREAD_QUERY } from './api'

/**
 * The inbox's unread counts for the navigation's badge (U-61). Asked when the app is entered, when it comes back to
 * the front (`visibilitychange`), every five minutes while it is in front and when the inbox is opened (its page
 * invalidates the query): never polled constantly, a million takers would make it the most frequent request
 * (docs/quizbuks/plans/Q7-messages-legal.md §9). A failure shows no badge.
 */
export function useUnread(enabled = true) {
  // Q59b: counted as the inbox lists (the sponsors' notices of the languages the taker sees).
  const settings = useSoundSetting()
  const languages = shownLanguages(settings.data, i18n.language)
  const unread = useQuery({
    queryKey: [...UNREAD_QUERY, languages],
    queryFn: () => readUnread(languages),
    enabled: enabled && !settings.isPending,
    refetchInterval: UNREAD_INTERVAL,
    refetchIntervalInBackground: false,
    // The return to the front is asked by `visibilitychange` below; a focus would ask a second time (review L4).
    refetchOnWindowFocus: false,
    staleTime: 30_000,
  })
  const { refetch } = unread
  useEffect(() => {
    if (!enabled) return
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refetch()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [enabled, refetch])
  return unread.data?.total ?? 0
}
