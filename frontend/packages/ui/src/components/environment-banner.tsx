import { EnvironmentBadge, type EnvironmentBadgeProps } from './environment-badge'

/** @deprecated The name from before phase 17zz27 (a full-width stripe); the same {@link EnvironmentBadge} now. */
export type EnvironmentBannerProps = EnvironmentBadgeProps

/** @deprecated Use {@link EnvironmentBadge}: the mark of a test system no longer takes a line of the page. */
export const EnvironmentBanner = EnvironmentBadge
