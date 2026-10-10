import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
  cn,
  PageHeader,
  UI_SCOPE,
} from '@jabiz/ui'
import { Fragment, type ReactNode } from 'react'
import { Link } from 'react-router'

/** A step of the breadcrumb: a link, or (the last) the current page. */
export interface Crumb {
  label: ReactNode
  to?: string
}

export interface AdminPageProps {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  /** The way here (Data › Carriers › History); the last step is the current page. */
  breadcrumb?: Crumb[]
  children?: ReactNode
  className?: string
}

/**
 * A page of the admin built with @jabiz/ui (decision D34): its header (breadcrumb, the one <h1>, actions) and
 * content, within the preflight's scope. Replaces ProComponents' `PageContainer`; the route that shows it carries
 * `handle: { ui: 'jabiz' }`, so the shell does not pin it to the light appearance.
 */
export default function AdminPage({ title, description, actions, breadcrumb, children, className }: AdminPageProps) {
  return (
    <div data-slot="admin-page" className={cn(UI_SCOPE, 'text-foreground flex flex-col gap-4', className)}>
      <PageHeader
        title={title}
        description={description}
        actions={actions}
        className="pb-0"
        breadcrumb={
          breadcrumb && breadcrumb.length > 0 ? (
            <Breadcrumb>
              <BreadcrumbList>
                {breadcrumb.map((crumb, index) => (
                  <Fragment key={index}>
                    {index > 0 && <BreadcrumbSeparator />}
                    <BreadcrumbItem>
                      {crumb.to && index < breadcrumb.length - 1 ? (
                        <BreadcrumbLink asChild>
                          <Link to={crumb.to}>{crumb.label}</Link>
                        </BreadcrumbLink>
                      ) : (
                        <BreadcrumbPage>{crumb.label}</BreadcrumbPage>
                      )}
                    </BreadcrumbItem>
                  </Fragment>
                ))}
              </BreadcrumbList>
            </Breadcrumb>
          ) : undefined
        }
      />
      {children}
    </div>
  )
}
