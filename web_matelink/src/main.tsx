import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import './presentation.css'
import { AppearanceProvider } from './components/Appearance'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppearanceProvider><App /></AppearanceProvider>
  </StrictMode>,
)
