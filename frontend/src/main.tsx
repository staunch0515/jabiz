import '@ant-design/v5-patch-for-react-19'
import { applyAppearance } from '@jabiz/ui'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import i18n from './i18n'
import './index.css'
import App from './App'
import { extension } from './extension'
import { registerExtensionMessages } from './extension/messages'

registerExtensionMessages(i18n, extension)
// The remembered appearance before the first paint, so a dark choice does not flash light.
applyAppearance()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
