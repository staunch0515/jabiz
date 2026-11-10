import { APP_BASE } from '../publishing/paths'

/** The app's page that verifies a certificate, from anywhere on this site (the address printed on the PDF). */
export function certificateVerifyLink(verifyKey: string, origin: string = window.location.origin): string {
  return `${origin}${APP_BASE}verify/${encodeURIComponent(verifyKey)}`
}
