import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import { testCookies } from "@/test/cookies";
import { problem, server, springUrl } from "@/test/msw";
import { captureRedirect } from "@/test/next";

import { login, logout } from "./auth";

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  Object.entries(fields).forEach(([name, value]) => data.set(name, value));
  return data;
}

describe("login", () => {
  it("stores the token in a cookie and opens the home page", async () => {
    server.use(
      http.post(springUrl("/api/sessions"), () =>
        HttpResponse.json(
          { token: "token-abc", user: { id: 1, email: "a@b.c", name: "A", role: "ADMIN" } },
          { status: 201 },
        ),
      ),
    );

    await expect(captureRedirect(() => login(undefined, form({ email: "a@b.c", password: "pw" })))).resolves.toBe("/");
    expect(testCookies.get("sentinel_session")).toBe("token-abc");
  });

  it("returns to the page the visitor came from", async () => {
    server.use(http.post(springUrl("/api/sessions"), () => HttpResponse.json({ token: "t" }, { status: 201 })));

    await expect(
      captureRedirect(() => login(undefined, form({ email: "a@b.c", password: "pw", from: "/runs?page=2" }))),
    ).resolves.toBe("/runs?page=2");
  });

  it.each(["https://evil.example/steal", "//evil.example", "javascript:alert(1)"])(
    "ignores an external redirect target %s",
    async (from) => {
      server.use(http.post(springUrl("/api/sessions"), () => HttpResponse.json({ token: "t" }, { status: 201 })));

      await expect(
        captureRedirect(() => login(undefined, form({ email: "a@b.c", password: "pw", from }))),
      ).resolves.toBe("/");
    },
  );

  it("keeps the wording identical for wrong password and unknown account", async () => {
    server.use(http.post(springUrl("/api/sessions"), () => problem(401, "email or password is incorrect")));

    await expect(login(undefined, form({ email: "a@b.c", password: "pw" }))).resolves.toEqual({
      error: "이메일 또는 비밀번호가 올바르지 않습니다",
    });
    expect(testCookies.has("sentinel_session")).toBe(false);
  });

  it("asks for both fields before calling Spring", async () => {
    await expect(login(undefined, form({ email: " ", password: "" }))).resolves.toEqual({
      error: "이메일과 비밀번호를 입력해 주세요",
    });
  });

  it("reports a server failure without details", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    server.use(http.post(springUrl("/api/sessions"), () => HttpResponse.error()));

    await expect(login(undefined, form({ email: "a@b.c", password: "pw" }))).resolves.toEqual({
      error: "로그인하지 못했습니다. API 서버 상태를 확인해 주세요",
    });
  });
});

describe("logout", () => {
  it("ends the session on the server and clears the cookie", async () => {
    testCookies.set("sentinel_session", "token-abc");
    let authorization: string | null = null;
    server.use(
      http.delete(springUrl("/api/sessions/current"), ({ request }) => {
        authorization = request.headers.get("authorization");
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await expect(captureRedirect(() => logout())).resolves.toBe("/login");

    expect(authorization).toBe("Bearer token-abc");
    expect(testCookies.has("sentinel_session")).toBe(false);
  });

  it("clears the cookie even when the server call fails", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    testCookies.set("sentinel_session", "token-abc");
    server.use(http.delete(springUrl("/api/sessions/current"), () => HttpResponse.error()));

    await expect(captureRedirect(() => logout())).resolves.toBe("/login");
    expect(testCookies.has("sentinel_session")).toBe(false);
  });
});
