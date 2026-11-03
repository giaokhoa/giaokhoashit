import tailwindcss from '@tailwindcss/vite';
import { reactRouter } from '@react-router/dev/vite';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [reactRouter(), tailwindcss()],
  // Package contracts xuất CommonJS: để Node nạp ngoài Vite SSR module runner.
  ssr: { external: ['@lms/contracts'] },
  server: {
    host: '0.0.0.0',
    port: 5173,
    // Cho phép mở bản dev qua đường hầm công khai khi review từ xa.
    allowedHosts: ['.lhr.life', '.trycloudflare.com', '.pinggy.net', '.pinggy-free.link'],
    proxy: {
      '/api': 'http://localhost:3000',
      // Kênh realtime của messaging (Socket.IO, namespace /messaging): ở production API phục vụ luôn socket trên cùng
      // origin nên chỉ môi trường dev mới cần chuyển tiếp cả HTTP polling lẫn WebSocket.
      '/socket.io': { target: 'http://localhost:3000', ws: true },
    },
  },
});
