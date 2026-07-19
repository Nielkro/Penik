import { defineConfig } from 'vite'
import compression from 'vite-plugin-compression'

export default defineConfig({
  plugins: [
    compression({ algorithm: 'gzip' }),
    compression({ algorithm: 'brotliCompress' })
  ]
})
