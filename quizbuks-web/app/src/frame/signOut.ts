import { useAuth } from '@jabiz/client'
import { forgetAttempts } from '../play/storage'
import { navigate } from '../qb/router'

/** Signs out: what the device kept of attempts is forgotten, then the sign-in page. One way, from "Me" and the welcome step. */
export function useSignOut(): () => void {
  const { signOut } = useAuth()
  return () => {
    void signOut().then(() => {
      forgetAttempts()
      navigate('/login', { replace: true })
    })
  }
}
