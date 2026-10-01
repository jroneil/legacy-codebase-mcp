import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Container image: emit .next/standalone so the runtime stage needs no full
  // node_modules install. `next dev` and `next start` on the host are unaffected.
  output: "standalone",
};

export default nextConfig;
