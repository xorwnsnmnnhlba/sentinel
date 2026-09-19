import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { problem, server, springUrl } from "@/test/msw";
import { captureNavigation } from "@/test/next";

import { apiError, apiGet, apiGetOrNotFound, apiSend, isApiError } from "./server";

describe("apiGet", () => {
  it("returns parsed JSON", async () => {
    server.use(http.get(springUrl("/api/projects"), () => HttpResponse.json([{ id: 1, name: "billing" }])));

    await expect(apiGet("/api/projects")).resolves.toEqual([{ id: 1, name: "billing" }]);
  });

  it("keeps query string and sends Accept header", async () => {
    let accept: string | null = null;
    let projectId: string | null = null;
    server.use(
      http.get(springUrl("/api/suites"), ({ request }) => {
        accept = request.headers.get("accept");
        projectId = new URL(request.url).searchParams.get("projectId");
        return HttpResponse.json([]);
      }),
    );

    await apiGet("/api/suites?projectId=7");

    expect(accept).toBe("application/json");
    expect(projectId).toBe("7");
  });

  it("throws ApiError with ProblemDetail detail", async () => {
    server.use(http.get(springUrl("/api/projects/9"), () => problem(404, "project 9 not found")));

    const error = await apiGet("/api/projects/9").catch((caught: unknown) => caught);

    expect(isApiError(error)).toBe(true);
    expect(error).toMatchObject({ status: 404, detail: "project 9 not found" });
  });

  it("falls back to status text when body is not JSON", async () => {
    server.use(
      http.get(
        springUrl("/api/projects"),
        () => new HttpResponse("<html>", { status: 502, statusText: "Bad Gateway" }),
      ),
    );

    await expect(apiGet("/api/projects")).rejects.toMatchObject({ status: 502, detail: "Bad Gateway" });
  });
});

describe("apiGetOrNotFound", () => {
  it("turns 404 into notFound()", async () => {
    server.use(http.get(springUrl("/api/suites/3"), () => problem(404, "test suite 3 not found")));

    await expect(captureNavigation(() => apiGetOrNotFound("/api/suites/3"))).resolves.toBe(
      "NEXT_HTTP_ERROR_FALLBACK;404",
    );
  });

  it("rethrows other errors", async () => {
    server.use(http.get(springUrl("/api/suites/3"), () => problem(500, "boom")));

    await expect(apiGetOrNotFound("/api/suites/3")).rejects.toMatchObject({ status: 500 });
  });
});

describe("apiSend", () => {
  it("sends JSON body with Content-Type", async () => {
    let received: unknown;
    let contentType: string | null = null;
    server.use(
      http.post(springUrl("/api/projects"), async ({ request }) => {
        received = await request.json();
        contentType = request.headers.get("content-type");
        return HttpResponse.json({ id: 1, name: "billing", description: null }, { status: 201 });
      }),
    );

    const created = await apiSend("POST", "/api/projects", { name: "billing" });

    expect(created).toEqual({ id: 1, name: "billing", description: null });
    expect(received).toEqual({ name: "billing" });
    expect(contentType).toBe("application/json");
  });

  it("returns null for 204 and sends no body", async () => {
    let body: string | undefined;
    server.use(
      http.delete(springUrl("/api/cases/5"), async ({ request }) => {
        body = await request.text();
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await expect(apiSend("DELETE", "/api/cases/5")).resolves.toBeNull();
    expect(body).toBe("");
  });

  it("throws ApiError on 409", async () => {
    server.use(http.post(springUrl("/api/projects"), () => problem(409, "project name 'a' is already in use")));

    await expect(apiSend("POST", "/api/projects", { name: "a" })).rejects.toMatchObject({ status: 409 });
  });
});

describe("isApiError", () => {
  it("recognizes only errors built by apiError", () => {
    expect(isApiError(apiError(400, "bad"))).toBe(true);
    expect(isApiError(new Error("plain"))).toBe(false);
    expect(isApiError({ name: "ApiError", status: 400 })).toBe(false);
    expect(isApiError(undefined)).toBe(false);
  });
});
