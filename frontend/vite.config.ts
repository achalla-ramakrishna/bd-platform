import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development, Vite serves the app and forwards API and sign-in calls to Spring Boot on 8080.
const backend = 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/login': backend,
      '/logout': backend,
      '/oauth2': backend,
      '/signed-out': backend,
    },
  },
  test: {
    environment: 'jsdom',
  },
});
