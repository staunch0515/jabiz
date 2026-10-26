import { Button } from '@jabiz/ui'
import { Eye } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { Link } from '../qb/router'

/** Opens the preview of what takers see (Q34b). */
export function PreviewButton({ to }: { to: string }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <Button asChild variant="outline">
      <Link to={to} data-testid="preview-open"><Eye aria-hidden />{t('preview.open')}</Link>
    </Button>
  )
}
