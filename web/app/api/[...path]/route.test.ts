import { http, HttpResponse } from "msw";
import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";

import { server, springUrl } from "@/test/msw";

import { DELETE, GET, PATCH, POST } from "./route";

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

  it("forwards body and Content-Type, returns only Content-Type and Location headers", async () => {
    let received: unknown;
    server.use(
      http.post(springUrl("/api/projects"), async ({ request }) => {
        received = await request.json();
        return HttpResponse.json(
          { id: 1 },
          { status: 201, headers: { Location: "/api/projects/1", "X-Internal": "secret", "Set-Cookie": "a=b" } },
        );
      }),
    );

    const response = await call(POST, ["projects"], {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: "billing" }),
    });

    expect(received).toEqual({ name: "billing" });
    expect(response.status).toBe(201);
    expect(response.headers.get("location")).toBe("/api/projects/1");
    expect(response.headers.get("content-type")).toContain("application/json");
    expect(response.headers.get("x-internal")).toBeNull();
    expect(response.headers.get("set-cookie")).toBeNull();
  });

  it("passes through Spring error status and body", async () => {
    server.use(
      http.patch(springUrl("/api/schedules/1"), () =>
        HttpResponse.json({ status: 400, detail: "invalid cron" }, { status: 400 }),
      ),
    );

    const response = await call(PATCH, ["schedules", "1"], {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({ detail: "invalid cron" });
  });

  it("forwards 204 without body", async () => {
    server.use(http.delete(springUrl("/api/cases/5"), () => new HttpResponse(null, { status: 204 })));

    const response = await call(DELETE, ["cases", "5"], { method: "DELETE" });

    expect(response.status).toBe(204);
    await expect(response.text()).resolves.toBe("");
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
