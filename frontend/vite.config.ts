import path from 'path';
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, '.', '');
    const backendApiBaseUrl = env.BACKEND_API_BASE_URL || 'http://localhost:8080';

    return {
      server: {
        port: 3000,
        host: '0.0.0.0',
        proxy: {
          '/api/canvases': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/canvas': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/assets': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/exports': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/image-jobs': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/agent': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/external': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/auth': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/user': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/stats': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/health': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/ws': {
            target: backendApiBaseUrl,
            changeOrigin: true,
            ws: true
          }
        }
      },
      plugins: [
        react()
      ],
      define: {
        'process.env.IMAGE_API_BASE_URL': JSON.stringify(env.IMAGE_API_BASE_URL),
        'process.env.IMAGE_API_MODEL': JSON.stringify(env.IMAGE_API_MODEL),
        'process.env.PROMPT_OPTIMIZER_MODEL': JSON.stringify(env.PROMPT_OPTIMIZER_MODEL),
        'process.env.CHAT_API_MODEL': JSON.stringify(env.CHAT_API_MODEL)
      },
      resolve: {
        alias: {
          '@': path.resolve(__dirname, '.'),
        }
      }
    };
});
