import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useUnreadConversations } from '../conversations/ConversationsPages'
import { useChecklist } from '../profile/useChecklist'
import { useMySponsor } from '../profile/api'
import { readTodo, TODO_QUERY, type TodoCounts } from './api'
import { todoItems, type TodoItem } from './todo'

/** How often the counts are asked again while the page is in front (as the menu's badge of the conversations). */
const TODO_INTERVAL = 5 * 60_000

/**
 * The counts of `qb.sponsor.todo`, read on entering, every five minutes and when the page comes back to the front, and
 * not at all while it is hidden (F-F3b: the same pace as the conversations' badge). A failure shows none. The menu's
 * badge and the home share the one query.
 */
export function useTodoCounts(enabled = true): TodoCounts | undefined {
  const todo = useQuery({ queryKey: TODO_QUERY, queryFn: () => readTodo(), enabled, refetchInterval: TODO_INTERVAL,
    refetchIntervalInBackground: false, refetchOnWindowFocus: false, staleTime: 30_000, retry: false })
  const { refetch } = todo
  useEffect(() => {
    if (!enabled) return
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refetch()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [enabled, refetch])
  return todo.data
}

/**
 * The home's list: the server's counts, the unread conversations, the application in review and what is missing of
 * the profile. Until the sponsor is approved only the application and the profile are asked about (the rest has
 * nothing to count yet). `ready` is true once the list is known, so "all done" is never said too early.
 */
export function useTodoItems(): { items: TodoItem[]; ready: boolean } {
  const sponsor = useMySponsor()
  const approved = sponsor.data?.reviewStatus === 'APPROVED'
  const counts = useTodoCounts(approved)
  const unread = useUnreadConversations(approved)
  const checklist = useChecklist()
  const missing = checklist.items.filter((item) => !item.optional && !item.done).length
  const items = todoItems({
    counts,
    unreadConversations: approved ? unread : 0,
    onboardingInReview: sponsor.data?.reviewStatus === 'PENDING',
    profileMissing: checklist.ready ? missing : 0,
  })
  return { items, ready: sponsor.isSuccess && checklist.ready && (!approved || counts !== undefined) }
}
