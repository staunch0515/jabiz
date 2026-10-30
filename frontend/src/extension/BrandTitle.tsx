import type { AdminBrand } from './api'
import { useBrandName } from './brand'

/** The brand's mark and name, as the sign-in card and the frame's corner show them. */
export function BrandTitle({ brand }: { brand: AdminBrand | undefined }) {
  const name = useBrandName(brand)
  if (!brand?.logo) return <>{name}</>
  return (
    <span className="inline-flex items-center gap-2" data-testid="brand-title">
      <img src={brand.logo} alt="" aria-hidden className="size-7 shrink-0" data-testid="brand-logo" />
      <span>{name}</span>
    </span>
  )
}
