import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The production API sends no CORS headers, so the web client talks
// to it same-origin: in development this proxy forwards the API
// paths to https://api.teslalink.joviluma.com; in production the
// static host must reverse-proxy the same paths (W5).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/v1': { target: 'https://api.teslalink.joviluma.com', changeOrigin: true },
      '/api': { target: 'https://api.teslalink.joviluma.com', changeOrigin: true },
    },
  },
})
