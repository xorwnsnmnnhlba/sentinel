import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Docker 실행용. .next/standalone에 server.js와 필요한 node_modules만 출력
  output: "standalone",
};

export default nextConfig;
