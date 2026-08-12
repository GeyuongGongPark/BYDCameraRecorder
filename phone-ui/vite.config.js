import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';
import { handleMockApi, mockPin } from './tools/mock-api.mjs';

const phoneUiRoot = fileURLToPath(new URL('.', import.meta.url));
function installDevelopmentRecorder(server) {
  server.middlewares.use((request, response, next) => {
    handleMockApi(request, response)
      .then((handled) => {
        if (!handled) {
          next();
        }
      })
      .catch(next);
  });
}

const developmentRecorder = {
  name: 'byd-development-recorder',
  configurePreviewServer: installDevelopmentRecorder,
  configureServer: installDevelopmentRecorder
};

export default defineConfig({
  base: './',
  define: {
    'import.meta.env.VITE_MOCK_PIN': JSON.stringify(mockPin)
  },
  build: {
    emptyOutDir: true,
    outDir: '../assets/phone',
    rollupOptions: {
      input: {
        app: `${phoneUiRoot}index.html`,
        qr: `${phoneUiRoot}qr.html`
      }
    },
    sourcemap: false,
    target: 'chrome74'
  },
  plugins: [developmentRecorder, svelte()],
  test: {
    environment: 'node'
  }
});
