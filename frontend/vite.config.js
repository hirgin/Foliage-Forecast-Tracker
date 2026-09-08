import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  // GitHub Pages serves a project site under /<repo>/, so asset URLs need that
  // prefix. Set BASE_PATH in CI; it stays '/' for local development.
  base: process.env.BASE_PATH || '/',
  plugins: [react()],
  test: {
    // Pure logic only -- no DOM needed. The colour ramp, date handling and
    // API path building are where the bugs have actually been.
    environment: 'node',
    include: ['src/**/*.test.js'],
  },
  server: {
    // The preview tooling hands the assigned port in PORT. Without this Vite
    // hunts for its own free port when 5173 is taken -- by a second session,
    // usually -- and the preview then points at a port nothing is serving.
    port: Number(process.env.PORT) || 5173,
    strictPort: Boolean(process.env.PORT),
    // Proxy keeps the browser on one origin in dev, so CORS only has to be
    // correct in deployed environments.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
