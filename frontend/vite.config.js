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
    port: 5173,
    // Proxy keeps the browser on one origin in dev, so CORS only has to be
    // correct in deployed environments.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
