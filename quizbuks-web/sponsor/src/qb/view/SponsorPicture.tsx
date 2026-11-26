import { cn } from '@jabiz/ui'
import { LazyImage } from './LazyImage'

/**
 * A sponsor's round picture in a list (Q113d-1; Q113 §9.1 "条目": 40 px beside a row, 32 px in a notification): their
 * avatar, else the first letter of their name on the accent colour. Decoration: the name is always written beside it.
 */
export function SponsorPicture({ avatar, name, size = 40, className }: {
  avatar: string | null | undefined
  /** The sponsor's name (else the quiz's), whose first letter stands in for a missing avatar. */
  name: string
  size?: 32 | 40
  className?: string }) {
  const box = size === 32 ? 'size-8 text-xs' : 'size-10 text-sm'
  return avatar
    ? <LazyImage fileId={avatar} variant="w128" alt="" className={cn(box, 'shrink-0 rounded-full', className)} />
    : (
      <span aria-hidden className={cn(box, 'bg-accent text-accent-foreground flex shrink-0 items-center justify-center rounded-full font-bold', className)}>
        {name.trim().charAt(0).toUpperCase()}
      </span>
    )
}
