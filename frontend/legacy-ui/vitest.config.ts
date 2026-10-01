import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

/**
 * Server-rendered inspection UI: components are plain React functions, so tests
 * render them with react-dom/server in a Node environment (no DOM required).
 */
export default defineConfig({
  resolve: {
    alias: { "@": fileURLToPath(new URL(".", import.meta.url)) },
  },
  test: {
    environment: "node",
    include: ["**/*.test.{ts,tsx}"],
    exclude: ["node_modules/**", ".next/**"],
  },
});
