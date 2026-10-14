import { useEffect, useRef, useState } from 'react'

/**
 * Moving an item of a list up or down keeps the keyboard where it was: once the list is drawn again in the new order
 * and its buttons are enabled again (`busy` is over), focus goes to the moved item's button of the same direction
 * (the other one at either end), and the new place is announced (`role="status"`).
 */
export function useMoveFocus(busy: boolean) {
  const [announcement, setAnnouncement] = useState('')
  const target = useRef<{ id: string; by: -1 | 1 } | null>(null)
  useEffect(() => {
    const moving = target.current
    if (!moving || busy) return
    target.current = null
    const button = (by: number) =>
      document.querySelector<HTMLButtonElement>(`[data-move="${CSS.escape(`${moving.id}:${by}`)}"]`)
    const same = button(moving.by)
    const next = same && !same.disabled ? same : button(-moving.by)
    next?.focus()
  })
  return {
    announcement,
    /** After a move: says where the item went and puts focus back on it. */
    moved: (id: string, by: -1 | 1, text: string) => {
      target.current = { id, by }
      setAnnouncement(text)
    },
  }
}
