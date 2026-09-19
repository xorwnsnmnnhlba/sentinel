import type { NextRequest } from "next/server";

import { apiFetch } from "@/lib/api/server";

/**
 * 브라우저용 BFF 프록시. /api/** 요청을 Spring /api/**로 서버사이드 전달하여 브라우저에 Spring 주소 비노출.
 * Server Component는 이 경로를 거치지 않고 lib/api/server로 Spring 직접 호출.
 * 응답 헤더는 Content-Type·Location만 전달
 */
async function forward(request: NextRequest, ctx: RouteContext<"/api/[...path]">): Promise<Response> {
  const { path } = await ctx.params;
  if (path.some((segment) => segment === ".." || segment === ".")) {
    return Response.json({ title: "Bad Request", status: 400, detail: "invalid path" }, { status: 400 });
  }

  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  const headers = new Headers({ Accept: request.headers.get("accept") ?? "application/json" });
  const contentType = request.headers.get("content-type");
  if (hasBody && contentType) {
    headers.set("Content-Type", contentType);
  }

  try {
    const upstream = await apiFetch(`/api/${path.map(encodeURIComponent).join("/")}${request.nextUrl.search}`, {
      method: request.method,
      headers,
      body: hasBody ? await request.text() : undefined,
    });
    const responseHeaders = new Headers();
    for (const name of ["content-type", "location"]) {
      const value = upstream.headers.get(name);
      if (value) {
        responseHeaders.set(name, value);
      }
    }
    return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
  } catch {
    // 내부 주소·스택이 노출되지 않도록 고정 메시지 반환
    return Response.json({ title: "Bad Gateway", status: 502, detail: "API server is unavailable" }, { status: 502 });
  }
}

export { forward as GET, forward as POST, forward as PUT, forward as PATCH, forward as DELETE };
