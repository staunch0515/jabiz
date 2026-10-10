import * as React from 'react'
import { ChevronLeftIcon, ChevronRightIcon, MoreHorizontalIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { cn, UI_SCOPE } from '../../lib/utils'
import { UI_NAMESPACE } from '../../i18n'
import { buttonVariants, type Button } from './button'

function Pagination({ className, ...props }: React.ComponentProps<'nav'>) {
  const { t } = useTranslation(UI_NAMESPACE)
  return (
    <nav
      aria-label={t('pagination.label')}
      data-slot="pagination"
      className={cn(UI_SCOPE, 'mx-auto flex w-full justify-center', className)}
      {...props}
    />
  )
}

function PaginationContent({ className, ...props }: React.ComponentProps<'ul'>) {
  return <ul data-slot="pagination-content" className={cn(UI_SCOPE, 'flex flex-row items-center gap-1', className)} {...props} />
}

function PaginationItem({ ...props }: React.ComponentProps<'li'>) {
  return <li data-slot="pagination-item" {...props} />
}

type PaginationLinkProps = {
  isActive?: boolean
} & Pick<React.ComponentProps<typeof Button>, 'size'> &
  React.ComponentProps<'a'>

function PaginationLink({ className, isActive, size = 'icon', ...props }: PaginationLinkProps) {
  return (
    <a
      aria-current={isActive ? 'page' : undefined}
      data-slot="pagination-link"
      data-active={isActive}
      className={cn(UI_SCOPE, buttonVariants({
          variant: isActive ? 'outline' : 'ghost',
          size,
        }),
        className,
      )}
      {...props}
    />
  )
}

function PaginationPrevious({ className, ...props }: React.ComponentProps<typeof PaginationLink>) {
  const { t } = useTranslation(UI_NAMESPACE)
  return (
    <PaginationLink aria-label={t('pagination.previous')} size="default" className={cn(UI_SCOPE, 'gap-1 px-2.5 sm:pl-2.5', className)} {...props}>
      <ChevronLeftIcon aria-hidden />
      <span className="hidden sm:block">{t('pagination.previousShort')}</span>
    </PaginationLink>
  )
}

function PaginationNext({ className, ...props }: React.ComponentProps<typeof PaginationLink>) {
  const { t } = useTranslation(UI_NAMESPACE)
  return (
    <PaginationLink aria-label={t('pagination.next')} size="default" className={cn(UI_SCOPE, 'gap-1 px-2.5 sm:pr-2.5', className)} {...props}>
      <span className="hidden sm:block">{t('pagination.nextShort')}</span>
      <ChevronRightIcon aria-hidden />
    </PaginationLink>
  )
}

function PaginationEllipsis({ className, ...props }: React.ComponentProps<'span'>) {
  const { t } = useTranslation(UI_NAMESPACE)
  return (
    <span aria-hidden data-slot="pagination-ellipsis" className={cn(UI_SCOPE, 'flex size-9 items-center justify-center', className)} {...props}>
      <MoreHorizontalIcon className="size-4" />
      <span className="sr-only">{t('pagination.morePages')}</span>
    </span>
  )
}

export {
  Pagination,
  PaginationContent,
  PaginationLink,
  PaginationItem,
  PaginationPrevious,
  PaginationNext,
  PaginationEllipsis,
}
