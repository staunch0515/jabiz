import { CertificateList } from '../qb/CertificateList'
import { SPONSOR_CERTIFICATES } from '../qb/certificates'
import { certificateVerifyLink } from './paths'

export const CERTIFICATES_QUERY = ['qb', 'sponsor-certificates'] as const

/**
 * The Sponsor's certificates (F-S5b, docs/quizbuks/plans/impl/F-S5.md section 3): those of the nodes they lit, from the
 * shared list: the PDF (issued on the first download), the verify link and the switch that turns the link off and back
 * on. A Sponsor's certificate is on no public page; only its link shows it.
 */
export function CertificatesPage() {
  return (
    <div className="flex flex-col gap-4" data-testid="sponsor-certificates">
      <CertificateList calls={SPONSOR_CERTIFICATES} queryKey={CERTIFICATES_QUERY} verifyLink={certificateVerifyLink} />
    </div>
  )
}
