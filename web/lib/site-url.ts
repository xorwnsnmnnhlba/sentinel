import "server-only";

import { headers } from "next/headers";

/** 사용자가 접속한 주소 (HTTPS 앞단을 거치면 전달된 호스트·프로토콜). 링크·명령 예시에 사용 */
export async function siteUrl(): Promise<string> {
  const header = await headers();
  const host = header.get("x-forwarded-host") ?? header.get("host") ?? "localhost:3000";
  const protocol = header.get("x-forwarded-proto") ?? (host.startsWith("localhost") ? "http" : "https");
  return `${protocol}://${host}`;
}
