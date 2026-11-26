import { useAuth } from '@jabiz/client'
import { cn } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useState, type HTMLAttributes } from 'react'
import { PROFILE_QUERY } from '../pages/ProfileForm'
import { readMyProfile } from '../qb/api'
import { AVATAR_QUERY, presetUrl, readMyAvatar } from './api'
import { initialBackground } from './colour'

type SpanProps = Omit<HTMLAttributes<HTMLSpanElement>, 'children'>

/**
 * The taker's initial in a circle of `className`'s size (Q114a): the first letter of the profile's name (else of the
 * address), white on the background their id gives them. Decorative: what holds it names the taker.
 */
export function InitialAvatar({ className, ...rest }: SpanProps) {
  const { email, userId } = useAuth()
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  const initial = (profile.data?.displayName || email || '?').trim().charAt(0).toUpperCase()
  return (
    <span aria-hidden {...rest} data-avatar="initial"
      className={cn(initialBackground(userId), 'text-qb-avatar-foreground flex shrink-0 items-center justify-center',
        'rounded-full font-bold', className)}>
      {initial}
    </span>
  )
}

/**
 * The taker's avatar in a circle of `className`'s size (Q114a, docs/quizbuks/plans/impl/Q114.md section 4.1): the
 * preset they chose, else their initial. Until it is read, when it cannot be read, and when the drawing does not load
 * (offline: the worker caches the shell only), the initial; never an error. Decorative, as the initial was.
 */
export function Avatar({ className, ...rest }: SpanProps) {
  const avatar = useQuery({ queryKey: AVATAR_QUERY, queryFn: readMyAvatar })
  const [broken, setBroken] = useState<string | null>(null)
  const preset = avatar.data?.kind === 'PRESET' ? avatar.data.preset : null
  if (!preset || broken === preset) return <InitialAvatar className={className} {...rest} />
  return (
    <span aria-hidden {...rest} data-avatar={preset}
      className={cn('flex shrink-0 overflow-hidden rounded-full', className)}>
      <img src={presetUrl(preset)} alt="" className="size-full" draggable={false} onError={() => setBroken(preset)} />
    </span>
  )
}
