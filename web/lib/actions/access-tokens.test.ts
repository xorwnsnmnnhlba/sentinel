import { http, HttpResponse } from "msw";
import { refresh } from "next/cache";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { codedProblem, problem, server, springUrl } from "@/test/msw";

import { issueAccessToken, revokeAccessToken } from "./access-tokens";

vi.mock("next/cache", () => ({ refresh: vi.fn() }));

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  Object.entries(fields).forEach(([name, value]) => data.set(name, value));
  return data;
}

function capture(method: "post" | "delete", path: string, response: () => Response) {
  const bodies: unknown[] = [];
  server.use(
    http[method](springUrl(path), async ({ request }) => {
      const text = await request.text();
      bodies.push(text === "" ? undefined : JSON.parse(text));
      return response();
    }),
  );
  return bodies;
}

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
});

afterEach(() => {
  vi.mocked(refresh).mockClear();
});

describe("issueAccessToken", () => {
  it("issues a token, shows it once and reloads the list", async () => {
    const bodies = capture("post", "/api/access-tokens", () =>
      HttpResponse.json(
        { token: "snt_abc", accessToken: { id: 1, expiresAt: "2026-12-29T00:00:00Z" } },
        { status: 201 },
      ),
    );

    await expect(
      issueAccessToken(undefined, form({ name: "  GitHub Actions  ", validityDays: "90" })),
    ).resolves.toEqual({ token: "snt_abc", expiresAt: "2026-12-29T00:00:00Z" });
    expect(bodies).toEqual([{ name: "GitHub Actions", validityDays: 90 }]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it.each([
    [{ name: " ", validityDays: "90" }, "토큰 이름(용도)을 입력해 주세요"],
    [{ name: "CI", validityDays: "7" }, "유효 기간을 선택해 주세요"],
  ])("explains invalid input without calling Spring: %j", async (fields, message) => {
    const bodies = capture("post", "/api/access-tokens", () => HttpResponse.json({}, { status: 201 }));

    await expect(issueAccessToken(undefined, form(fields))).resolves.toEqual({ error: message });
    expect(bodies).toEqual([]);
  });

  it("names a coded error in Korean", async () => {
    capture("post", "/api/access-tokens", () =>
      codedProblem(400, "name must be at most 100 characters", "TOO_LONG", { target: "name", max: "100" }),
    );

    await expect(issueAccessToken(undefined, form({ name: "CI", validityDays: "30" }))).resolves.toEqual({
      error: "이름: 100자 이하로 입력해 주세요",
    });
  });

  it("shows what Spring rejected", async () => {
    capture("post", "/api/access-tokens", () => problem(400, "name must be at most 100 characters"));

    await expect(issueAccessToken(undefined, form({ name: "CI", validityDays: "30" }))).resolves.toEqual({
      error: "입력값을 확인해 주세요: name must be at most 100 characters",
    });
  });
});

describe("revokeAccessToken", () => {
  it("revokes the token and reloads the list", async () => {
    const bodies = capture("delete", "/api/access-tokens/4", () => new HttpResponse(null, { status: 204 }));

    await expect(revokeAccessToken(4)).resolves.toEqual({});
    expect(bodies).toEqual([undefined]);
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("explains a token that is already gone and reloads to show it", async () => {
    capture("delete", "/api/access-tokens/4", () => problem(404, "access token 4 not found"));

    await expect(revokeAccessToken(4)).resolves.toEqual({ error: "이미 폐기된 토큰입니다" });
    expect(refresh).toHaveBeenCalledOnce();
  });
});
