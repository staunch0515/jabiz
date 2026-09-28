import { useT } from '../i18n/locale'

/**
 * The page's title and description (design section 9.4), with React 19's document metadata: rendered here, placed in
 * the document head.
 */
export function PageMeta({ title, description }: { title?: string; description?: string }) {
  const t = useT()
  const site = t('site.name')
  return (
    <>
      <title>{title ? `${title} · ${site}` : site}</title>
      {description ? <meta name="description" content={description} /> : null}
    </>
  )
}
