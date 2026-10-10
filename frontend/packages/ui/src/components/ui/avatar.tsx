import * as React from 'react'
import { Avatar as AvatarPrimitive } from 'radix-ui'

import { cn, UI_SCOPE } from '../../lib/utils'

function Avatar({ className, ...props }: React.ComponentProps<typeof AvatarPrimitive.Root>) {
  return (
    <AvatarPrimitive.Root
      data-slot="avatar"
      className={cn(UI_SCOPE, 'relative flex size-8 shrink-0 overflow-hidden rounded-full', className)}
      {...props}
    />
  )
}

function AvatarImage({ className, ...props }: React.ComponentProps<typeof AvatarPrimitive.Image>) {
  return <AvatarPrimitive.Image data-slot="avatar-image" className={cn(UI_SCOPE, 'aspect-square size-full', className)} {...props} />
}

function AvatarFallback({ className, ...props }: React.ComponentProps<typeof AvatarPrimitive.Fallback>) {
  return (
    <AvatarPrimitive.Fallback
      data-slot="avatar-fallback"
      className={cn(UI_SCOPE, 'bg-muted flex size-full items-center justify-center rounded-full', className)}
      {...props}
    />
  )
}

export { Avatar, AvatarImage, AvatarFallback }
