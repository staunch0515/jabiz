import { OidcCallback } from '@jabiz/ui'
import { useLocation, useNavigate } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { takeBinder, takeReturnPath } from '../auth/oidc'
import { extension } from '../extension'
import { homePath } from '../extension/registry'

/**
 * Where an identity provider sends the user back (docs/design/10-security.md section 12): @jabiz/ui's OidcCallback
 * (decision D43) on the admin's router; a second step continues on the sign-in page.
 */
export default function OidcCallbackPage() {
  const { signInWithProvider } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  return (
    <OidcCallback
      search={location.search}
      fallbackPath={homePath(extension)}
      auth={{ signInWithProvider }}
      takeBinder={takeBinder}
      takeReturnPath={takeReturnPath}
      onSignedIn={(path) => void navigate(path, { replace: true })}
      onSecondStep={(step, path) => void navigate('/login', { replace: true, state: { from: path, step } })}
      onBack={() => void navigate('/login', { replace: true })}
    />
  )
}
