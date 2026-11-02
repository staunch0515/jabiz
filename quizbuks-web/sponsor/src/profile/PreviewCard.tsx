import { Badge, Card, CardContent } from '@jabiz/ui'
import { Store } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import type { SponsorPageStatus } from '../page/api'
import { FileImage } from '../qb/FileImage'
import { sponsorPageLink } from '../qb/page'

export interface PreviewValues {
  company: string | null
  avatar: string | null
  headline: string
  cover: string | null
  handle: string
  status: SponsorPageStatus | 'NONE'
}

/**
 * The shop page as a small card (Q79, D-Q79-4/5): the cover, the logo (the basic profile's avatar), the company,
 * the headline and the link. The overview draws it from what is stored, the page editor from the form's values as
 * they are typed. A picture of the page, not the page: the app draws the real one.
 */
export function PreviewCard({ values, testId = 'profile-preview' }: { values: PreviewValues; testId?: string }) {
  const { t } = useTranslation(SPONSOR)
  const variant = values.status === 'PUBLISHED' ? 'success'
    : values.status === 'REJECTED' || values.status === 'TAKEN_DOWN' ? 'destructive' : 'secondary'
  return (
    <Card className="overflow-hidden py-0" data-testid={testId} data-status={values.status}>
      <div className="bg-muted relative aspect-video w-full">
        {values.cover
          ? <FileImage fileId={values.cover} variant="w640" alt="" className="size-full object-cover" />
          : <Store aria-hidden className="text-muted-foreground absolute inset-0 m-auto size-10" />}
        <Badge variant={variant} className="absolute top-2 right-2" data-testid={`${testId}-status`}>
          {t(`page.status.${values.status}`)}
        </Badge>
      </div>
      <CardContent className="flex flex-col gap-2 py-4">
        <div className="flex items-center gap-3">
          {values.avatar
            ? <FileImage fileId={values.avatar} variant="w128" alt="" className="size-10 rounded-full border" />
            : <span aria-hidden className="bg-muted size-10 rounded-full border" />}
          <div className="flex min-w-0 flex-col">
            <span className="truncate font-medium" data-testid={`${testId}-company`}>
              {values.company || t('profile.preview.noCompany')}
            </span>
            <span className="text-muted-foreground truncate text-sm" data-testid={`${testId}-headline`}>
              {values.headline || t('profile.preview.noHeadline')}
            </span>
          </div>
        </div>
        <p className="text-muted-foreground truncate text-xs" data-testid={`${testId}-link`}>
          {sponsorPageLink(values.handle || t('profile.preview.handlePlaceholder'))}
        </p>
      </CardContent>
    </Card>
  )
}
