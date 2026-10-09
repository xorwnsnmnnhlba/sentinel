import { http, HttpResponse } from "msw";
import { refresh } from "next/cache";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { testCookies } from "@/test/cookies";
import { problem, server, springUrl } from "@/test/msw";
import { captureRedirect } from "@/test/next";

import { acceptInvite, changePassword, createPasswordReset, deleteUser, invite, resetPassword } from "./users";

vi.mock("next/cache", () => ({ refresh: vi.fn() }));

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  Object.entries(fields).forEach(([name, value]) => data.set(name, value));
  return data;
}

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
});

describe("invite", () => {
  it("builds the link from the issued token", async () => {
    let received: unknown;
    server.use(
      http.post(springUrl("/api/invites"), async ({ request }) => {
        received = await request.json();
        return HttpResponse.json({ token: "invite-token", expiresAt: "2026-09-27T00:00:00Z" }, { status: 201 });
      }),
    );

    await expect(
      invite("https://sentinel.example.com", undefined, form({ email: " a@b.c ", role: "ADMIN" })),
    ).resolves.toEqual({
      link: "https://sentinel.example.com/invites/invite-token",
      expiresAt: "2026-09-27T00:00:00Z",
    });
    expect(received).toEqual({ email: "a@b.c", role: "ADMIN" });
  });

  it("defaults to the member role and requires an email", async () => {
    let received: unknown;
    server.use(
      http.post(springUrl("/api/invites"), async ({ request }) => {
        received = await request.json();
        return HttpResponse.json({ token: "t" }, { status: 201 });
      }),
    );

    await expect(invite("https://x", undefined, form({ email: " " }))).resolves.toEqual({
      error: "이메일을 입력해 주세요",
    });
    await invite("https://x", undefined, form({ email: "a@b.c" }));
    expect(received).toMatchObject({ role: "MEMBER" });
  });

  it("explains that the email is taken", async () => {
    server.use(http.post(springUrl("/api/invites"), () => problem(409, "email is already in use")));

    await expect(invite("https://x", undefined, form({ email: "a@b.c" }))).resolves.toEqual({
      error: "이미 사용 중인 이메일입니다",
    });
  });

  it("explains that only administrators may invite", async () => {
    server.use(http.post(springUrl("/api/invites"), () => problem(403, "administrator role is required")));

    await expect(invite("https://x", undefined, form({ email: "a@b.c" }))).resolves.toEqual({
      error: "권한이 없습니다. 관리자만 사용할 수 있습니다",
    });
  });
});

describe("password reset link", () => {
  it("builds the link for a user", async () => {
    let received: unknown;
    server.use(
      http.post(springUrl("/api/password-resets"), async ({ request }) => {
        received = await request.json();
        return HttpResponse.json({ token: "reset-token", expiresAt: "2026-09-20T02:00:00Z" }, { status: 201 });
      }),
    );

    await expect(createPasswordReset("https://sentinel.example.com", 7)).resolves.toEqual({
      link: "https://sentinel.example.com/password-resets/reset-token",
      expiresAt: "2026-09-20T02:00:00Z",
    });
    expect(received).toEqual({ userId: 7 });
  });
});

describe("deleteUser", () => {
  it("refreshes the list after deleting", async () => {
    server.use(http.delete(springUrl("/api/users/7"), () => new HttpResponse(null, { status: 204 })));

    await expect(deleteUser(7)).resolves.toEqual({});
    expect(refresh).toHaveBeenCalledOnce();
  });

  it("shows why the delete was refused", async () => {
    server.use(http.delete(springUrl("/api/users/7"), () => problem(400, "the last administrator cannot be deleted")));

    await expect(deleteUser(7)).resolves.toEqual({
      error: "입력값을 확인해 주세요: the last administrator cannot be deleted",
    });
  });
});

describe("changePassword", () => {
  it("sends both passwords and asks for a new login", async () => {
    let received: unknown;
    server.use(
      http.put(springUrl("/api/users/me/password"), async ({ request }) => {
        received = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await expect(
      captureRedirect(() =>
        changePassword(
          undefined,
          form({ currentPassword: "old", newPassword: "new-password", newPasswordConfirmation: "new-password" }),
        ),
      ),
    ).resolves.toBe("/login?changed=1");
    expect(received).toEqual({ currentPassword: "old", newPassword: "new-password" });
  });

  it("checks the confirmation before calling Spring", async () => {
    await expect(
      changePassword(
        undefined,
        form({ currentPassword: "old", newPassword: "new-password", newPasswordConfirmation: "typo" }),
      ),
    ).resolves.toEqual({ error: "새 비밀번호가 서로 다릅니다" });
  });

  it("reports a wrong current password", async () => {
    server.use(http.put(springUrl("/api/users/me/password"), () => problem(401, "email or password is incorrect")));

    await expect(
      changePassword(
        undefined,
        form({ currentPassword: "wrong", newPassword: "new-password", newPasswordConfirmation: "new-password" }),
      ),
    ).resolves.toEqual({ error: "현재 비밀번호가 올바르지 않습니다" });
  });

  it("reports a too short password", async () => {
    server.use(
      http.put(springUrl("/api/users/me/password"), () => problem(400, "password must be at least 10 characters")),
    );

    await expect(
      changePassword(
        undefined,
        form({ currentPassword: "old", newPassword: "short", newPasswordConfirmation: "short" }),
      ),
    ).resolves.toEqual({ error: "비밀번호는 10자 이상이어야 합니다" });
  });
});

describe("acceptInvite", () => {
  it("creates the account, signs in with it and opens the home page", async () => {
    const requests: unknown[] = [];
    server.use(
      http.post(springUrl("/api/users"), async ({ request }) => {
        requests.push(await request.json());
        return HttpResponse.json(
          { id: 2, email: "member@sentinel.test", name: "구성원", role: "MEMBER" },
          { status: 201 },
        );
      }),
      http.post(springUrl("/api/sessions"), async ({ request }) => {
        requests.push(await request.json());
        return HttpResponse.json({ token: "session-token", user: { id: 2 } });
      }),
    );

    await expect(
      captureRedirect(() =>
        acceptInvite(
          "invite-token",
          undefined,
          form({ name: "구성원", password: "member-password", passwordConfirmation: "member-password" }),
        ),
      ),
    ).resolves.toBe("/");
    expect(testCookies.get("sentinel_session")).toBe("session-token");
    expect(requests).toEqual([
      { inviteToken: "invite-token", name: "구성원", password: "member-password" },
      { email: "member@sentinel.test", password: "member-password" },
    ]);
  });

  it("explains an expired or used link", async () => {
    server.use(http.post(springUrl("/api/users"), () => problem(404, "link 0 not found")));

    await expect(
      acceptInvite(
        "old-token",
        undefined,
        form({ name: "a", password: "member-password", passwordConfirmation: "member-password" }),
      ),
    ).resolves.toEqual({ error: "링크가 만료되었거나 이미 사용되었습니다. 새 링크를 요청해 주세요" });
    expect(testCookies.has("sentinel_session")).toBe(false);
  });
});

describe("resetPassword", () => {
  it("sets the new password and sends the visitor to the login page", async () => {
    let received: unknown;
    server.use(
      http.get(springUrl("/api/password-resets/reset-token"), () =>
        HttpResponse.json({ userId: 7, email: "member@sentinel.test", expiresAt: "2026-09-20T02:00:00Z" }),
      ),
      http.put(springUrl("/api/users/7/password"), async ({ request }) => {
        received = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await expect(
      captureRedirect(() =>
        resetPassword(
          "reset-token",
          undefined,
          form({ password: "brand-new-password", passwordConfirmation: "brand-new-password" }),
        ),
      ),
    ).resolves.toBe("/login?changed=1");
    expect(received).toEqual({ resetToken: "reset-token", password: "brand-new-password" });
  });

  it("explains an expired or used link", async () => {
    server.use(http.get(springUrl("/api/password-resets/old-token"), () => problem(404, "link 0 not found")));

    await expect(
      resetPassword(
        "old-token",
        undefined,
        form({ password: "brand-new-password", passwordConfirmation: "brand-new-password" }),
      ),
    ).resolves.toEqual({ error: "링크가 만료되었거나 이미 사용되었습니다. 새 링크를 요청해 주세요" });
  });

  it("checks the confirmation", async () => {
    await expect(
      resetPassword("reset-token", undefined, form({ password: "brand-new-password", passwordConfirmation: "typo" })),
    ).resolves.toEqual({ error: "비밀번호가 서로 다릅니다" });
  });
});
