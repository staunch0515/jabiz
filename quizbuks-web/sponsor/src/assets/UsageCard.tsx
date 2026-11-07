import { formatDate } from '@jabiz/client'
import { Alert, AlertDescription, AlertTitle, Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { HardDrive, TriangleAlert } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { problemText } from '../qb/problems'
import { formatBytes, readUsage, USAGE_QUERY, usedPercent } from './usage'
import './messages'

/**
 * The top of the asset library (F-C2): "used X / free Y", the files and the assets, as of the latest daily snapshot,
 * with what is counted ("as uploaded, without thumbnails or other variants") and that nothing is charged in the first
 * launch. Above the free amount a banner says so (only says so: uploads go on) and offers the unused assets.
 */
export function UsageCard({ onShowUnused }: { onShowUnused?: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const usage = useQuery({ queryKey: USAGE_QUERY, queryFn: readUsage })
  if (usage.isPending) return null
  if (usage.isError) {
    return <p className="text-muted-foreground text-sm" data-testid="usage-failed">{t('assets.usage.failed', {
      reason: problemText(usage.error) })}</p>
  }
  const data = usage.data
  const used = formatBytes(data.storageBytes)
  const free = data.freeBytes == null ? null : formatBytes(data.freeBytes)
  const percent = usedPercent(data)
  return (
    <div className="flex flex-col gap-3" data-testid="usage">
      {data.overFree && free && (
        <Alert variant="warning" data-testid="usage-over">
          <TriangleAlert aria-hidden />
          <AlertTitle>{t('assets.usage.overTitle')}</AlertTitle>
          <AlertDescription className="flex flex-col items-start gap-2">
            <span>{t('assets.usage.over', { used, free })}</span>
            {onShowUnused && (
              <Button variant="outline" size="sm" data-testid="usage-show-unused" onClick={onShowUnused}>
                {t('assets.usage.showUnused')}
              </Button>
            )}
          </AlertDescription>
        </Alert>
      )}
      <Card className="gap-3 py-4" data-testid="usage-card">
        <CardHeader className="px-4">
          <CardTitle className="flex items-center gap-2 text-base">
            <HardDrive aria-hidden className="size-4" />{t('assets.usage.title')}
          </CardTitle>
          <CardDescription>
            {data.day ? t('assets.usage.asOf', { day: formatDate(data.day) }) : t('assets.usage.none')}
          </CardDescription>
        </CardHeader>
        {data.day && (
          <CardContent className="flex flex-col gap-2 px-4">
            <p className="font-medium" data-testid="usage-used">
              {free ? t('assets.usage.used', { used, free }) : t('assets.usage.usedNoFree', { used })}
            </p>
            {percent != null && (
              <div role="meter" aria-label={t('assets.usage.meter')} aria-valuemin={0} aria-valuemax={100}
                aria-valuenow={percent} aria-valuetext={t('assets.usage.used', { used, free })}
                className="bg-muted h-2 w-full overflow-hidden rounded-full" data-testid="usage-meter">
                <div className={data.overFree ? 'bg-warning h-full' : 'bg-primary h-full'}
                  style={{ width: `${percent}%` }} />
              </div>
            )}
            <p className="text-muted-foreground flex flex-wrap gap-x-4 text-sm">
              <span data-testid="usage-files">{t('assets.usage.files', { count: data.storageFiles ?? 0 })}</span>
              <span data-testid="usage-assets">{t('assets.usage.assets', { count: data.assetsCount ?? 0 })}</span>
            </p>
          </CardContent>
        )}
        <CardContent className="text-muted-foreground flex flex-col gap-1 px-4 text-xs">
          <span>{t('assets.usage.variants')}</span>
          <span>{t('assets.usage.free')}</span>
        </CardContent>
      </Card>
    </div>
  )
}
