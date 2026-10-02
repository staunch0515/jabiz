/** The fixed asset pages' paths (outside the platform's own, decision D22). */
export const ASSETS_PATH = '/assets'
export const DEPRECIATION_PATH = '/assets/depreciation'
export const assetPath = (assetId: string) => `/assets/${encodeURIComponent(assetId)}`
