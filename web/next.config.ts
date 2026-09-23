import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Docker 실행용. .next/standalone에 server.js와 필요한 node_modules만 출력
  output: "standalone",
  experimental: {
    serverActions: {
      // 케이스 가져오기의 OpenAPI 문서(최대 5MB, Spring 한도와 동일) 전송용. 기본값 1MB.
      // 멀티바이트 문자·요청 구조분 여유를 둠. 모든 Server Action에 적용되는 한도
      bodySizeLimit: "6mb",
    },
  },
};

export default nextConfig;
