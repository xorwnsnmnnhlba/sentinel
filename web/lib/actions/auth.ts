"use server";

import { redirect } from "next/navigation";

import { apiFetch, isApiError } from "@/lib/api/server";
import { clearSessionCookie, setSessionCookie } from "@/lib/api/session";
import type { AuthenticatedUser } from "@/lib/api/types";
import { safeRedirectPath } from "@/lib/redirect-path";

/**
 * 로그인·로그아웃. 세션 토큰은 httpOnly 쿠키로만 보관하고 브라우저 스크립트에서 읽을 수 없음.
 */

export type LoginState = { error?: string } | undefined;

export async function login(_: LoginState, form: FormData): Promise<LoginState> {
  const email = String(form.get("email") ?? "").trim();
  const password = String(form.get("password") ?? "");
  if (email === "" || password === "") {
    return { error: "이메일과 비밀번호를 입력해 주세요" };
  }

  let token: string;
  try {
    const response = await apiFetch("/api/sessions", {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({ email, password }),
    });
    if (response.status === 401) {
      // 계정 존재 여부를 알리지 않도록 서버와 같은 문구 유지
      return { error: "이메일 또는 비밀번호가 올바르지 않습니다" };
    }
    if (!response.ok) {
      return { error: "로그인하지 못했습니다. 잠시 후 다시 시도해 주세요" };
    }
    ({ token } = (await response.json()) as { token: string; user: AuthenticatedUser });
  } catch (error) {
    console.error("login failed", isApiError(error) ? error.detail : error);
    return { error: "로그인하지 못했습니다. API 서버 상태를 확인해 주세요" };
  }

  await setSessionCookie(token);
  redirect(safeRedirectPath(form.get("from")));
}

export async function logout(): Promise<void> {
  try {
    await apiFetch("/api/sessions/current", { method: "DELETE" });
  } catch (error) {
    // 서버에서 지우지 못해도 브라우저 쿠키는 삭제
    console.error("logout failed", error);
  }
  await clearSessionCookie();
  redirect("/login");
}
