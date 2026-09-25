import { http, HttpResponse } from "msw";
import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";

import { server, springUrl } from "@/test/msw";

import * as route from "./route";
import { GET } from "./route";

function call(
  handler: typeof GET,
  path: string[],
  init: { method?: string; search?: string; headers?: Record<string, string>; body?: string } = {},
) {
  const url = `http://localhost:3000/api/${path.join("/")}${init.search ?? ""}`;
  const request = new NextRequest(url, { method: init.method ?? "GET", headers: init.headers, body: init.body });
  return handler(request, { params: Promise.resolve({ path }) });
}

describe("BFF proxy", () => {
  it("forwards GET with query string to Spring", async () => {
    server.use(
      http.get(springUrl("/api/runs"), ({ request }) =>
        HttpResponse.json({ page: new URL(request.url).searchParams.get("page") }),
      ),
    );

    const response = await call(GET, ["runs"], { search: "?page=2" });

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({ page: "2" });
  });

  it("returns only Content-Type and Location headers", async () => {
    server.use(
      http.get(springUrl("/api/projects"), () =>
        HttpResponse.json([], { headers: { Location: "/api/projects", "X-Internal": "secret", "Set-Cookie": "a=b" } }),
      ),
    );

    const response = await call(GET, ["projects"]);

    expect(response.headers.get("content-type")).toContain("application/json");
    expect(response.headers.get("location")).toBe("/api/projects");
    expect(response.headers.get("x-internal")).toBeNull();
    expect(response.headers.get("set-cookie")).toBeNull();
  });

  it("passes through Spring error status and body", async () => {
    server.use(
      http.get(springUrl("/api/runs/9"), () =>
        HttpResponse.json({ status: 404, detail: "run 9 not found" }, { status: 404 }),
      ),
    );

    const response = await call(GET, ["runs", "9"]);

    expect(response.status).toBe(404);
    await expect(response.json()).resolves.toMatchObject({ detail: "run 9 not found" });
  });

  it("is read-only: writes go through Server Actions, which check the request origin", () => {
    // 내보내지 않은 메서드는 Next.js가 405로 응답
    expect(Object.keys(route).sort()).toEqual(["GET"]);
  });

  it.each([[[".."]], [["runs", "..", "admin"]], [["."]]])("rejects dot segments %j", async (path) => {
    const response = await call(GET, path);

    expect(response.status).toBe(400);
  });

  it("encodes path segments so they cannot add new segments", async () => {
    let requested = "";
    server.use(
      http.get(springUrl("/api/runs/:id"), ({ request }) => {
        requested = new URL(request.url).pathname;
        return HttpResponse.json({});
      }),
    );

    await call(GET, ["runs", "a/b"]);

    expect(requested).toBe("/api/runs/a%2Fb");
  });

  it("returns 502 without internal details when Spring is down", async () => {
    server.use(http.get(springUrl("/api/projects"), () => HttpResponse.error()));

    const response = await call(GET, ["projects"]);

    expect(response.status).toBe(502);
    const body = await response.json();
    expect(body).toEqual({ title: "Bad Gateway", status: 502, detail: "API server is unavailable" });
    expect(JSON.stringify(body)).not.toContain("8080");
  });
});
