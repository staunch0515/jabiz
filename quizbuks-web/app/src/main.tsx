import { configureClient, registerServiceWorker } from '@jabiz/client'
import { applyAppearance } from '@jabiz/ui'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'
import './messages'
import './qb/kudos'

// The app's sign-in entry (jabizApp passes it as VITE_JABIZ_ENTRY; named here as well for `pnpm dev`): its sessions,
// kept on the device for 30 days, and its identity providers are the app's, apart from the sponsor console's and the
// administration's in the same browser.
configureClient({ entry: 'app' })
// The remembered appearance before the first paint.
applyAppearance()
// The PWA's Service Worker (VITE_JABIZ_PWA=on in the jar's build); a new version waits for PwaUpdatePrompt.
void registerServiceWorker()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
