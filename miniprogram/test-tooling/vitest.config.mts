import { defineConfig } from 'vitest/config'
import { fileURLToPath } from 'node:url'

// Taro's optional Vite 4 compiler peer and the current test runner have separate locks.
export default defineConfig({
  resolve: { alias: { vitest: fileURLToPath(new URL('./node_modules/vitest/dist/index.js', import.meta.url)) } },
  test: { include: ['src/**/*.test.{ts,tsx}', 'tests/**/*.test.{ts,tsx}'] },
})
