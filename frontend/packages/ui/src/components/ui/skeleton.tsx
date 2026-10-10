import * as React from 'react'

import { cn, UI_SCOPE } from '../../lib/utils'

function Skeleton({ className, ...props }: React.ComponentProps<'div'>) {
  return <div data-slot="skeleton" className={cn(UI_SCOPE, 'bg-accent animate-pulse rounded-md', className)} {...props} />
}

export { Skeleton }
