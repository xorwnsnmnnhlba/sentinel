"use server";

import { refresh } from "next/cache";
import { redirect } from "next/navigation";

import { apiGet, apiSend, isApiError } from "@/lib/api/server";
import { setSessionCookie } from "@/lib/api/session";
import type { AuthenticatedUser, IssuedLink, PasswordResetLinkInfo, UserSummary } from "@/lib/api/types";

/**
 * 사용자 초대·초대 취소·삭제, 비밀번호 변경·재설정.
 * 초대·재설정 링크의 토큰은 발급 응답에서만 확인할 수 있어 화면에 한 번만 표시
 */

export type ActionState = { error?: string } | undefined;

/** 링크 발급 결과. 화면에서 복사하여 전달 */
export type LinkState = { link?: string; expiresAt?: string; error?: string } | undefined;

export async function invite(baseUrl: string, _: LinkState, form: FormData): Promise<LinkState> {
  const email = String(form.get("email") ?? "").trim();
  const role = form.get("role") === "ADMIN" ? "ADMIN" : "MEMBER";
  if (email === "") {
    return { error: "이메일을 입력해 주세요" };
  }
  try {
    const issued = await apiSend<IssuedLink>("POST", "/api/invites", { email, role });
    // 대기 중인 초대 목록에 바로 보이도록. 링크를 보여 주는 대화상자의 상태는 유지됨
    refresh();
    return { link: `${baseUrl}/invites/${issued!.token}`, expiresAt: issued!.expiresAt };
  } catch (error) {
    return { error: message(error) };
  }
}

/** 초대 취소. 보낸 링크는 바로 쓸 수 없게 됨 */
export async function revokeInvite(inviteId: number): Promise<ActionState> {
  // 바인딩된 인자도 클라이언트가 바꿀 수 있으므로 숫자 ID만 (다른 API 경로가 되지 않도록)
  if (!Number.isSafeInteger(inviteId) || inviteId <= 0) {
    return { error: "취소할 초대를 다시 선택해 주세요" };
  }
  try {
    await apiSend("DELETE", `/api/pending-invites/${inviteId}`);
  } catch (error) {
    if (isApiError(error) && error.status === 404) {
      refresh();
      return { error: "이미 수락·취소되었거나 만료된 초대입니다" };
    }
    return { error: message(error) };
  }
  refresh();
  return {};
}

export async function createPasswordReset(baseUrl: string, userId: number): Promise<LinkState> {
  try {
    const issued = await apiSend<IssuedLink>("POST", "/api/password-resets", { userId: Number(userId) });
    return { link: `${baseUrl}/password-resets/${issued!.token}`, expiresAt: issued!.expiresAt };
  } catch (error) {
    return { error: message(error) };
  }
}

export async function deleteUser(userId: number): Promise<ActionState> {
  // 바인딩된 인자도 클라이언트가 바꿀 수 있으므로 숫자 ID만 ("1/../../projects/5" 같은 값이 다른 API 경로가 되지 않도록)
  if (!Number.isSafeInteger(userId) || userId <= 0) {
    return { error: "삭제할 사용자를 다시 선택해 주세요" };
  }
  try {
    await apiSend("DELETE", `/api/users/${userId}`);
  } catch (error) {
    return { error: message(error) };
  }
  refresh();
  return {};
}

/** 비밀번호 변경. 변경 후 모든 세션이 끊기므로 다시 로그인 */
export async function changePassword(_: ActionState, form: FormData): Promise<ActionState> {
  const currentPassword = String(form.get("currentPassword") ?? "");
  const newPassword = String(form.get("newPassword") ?? "");
  const confirmation = String(form.get("newPasswordConfirmation") ?? "");
  if (currentPassword === "" || newPassword === "") {
    return { error: "현재 비밀번호와 새 비밀번호를 입력해 주세요" };
  }
  if (newPassword !== confirmation) {
    return { error: "새 비밀번호가 서로 다릅니다" };
  }
  try {
    await apiSend("PUT", "/api/users/me/password", { currentPassword, newPassword });
  } catch (error) {
    if (isApiError(error) && error.status === 401) {
      return { error: "현재 비밀번호가 올바르지 않습니다" };
    }
    return { error: message(error) };
  }
  redirect("/login?changed=1");
}

/** 초대 수락. 계정을 만들고(초대 토큰) 그 비밀번호로 로그인하여 바로 로그인 상태로 전환 */
export async function acceptInvite(token: string, _: ActionState, form: FormData): Promise<ActionState> {
  const name = String(form.get("name") ?? "").trim();
  const password = String(form.get("password") ?? "");
  const confirmation = String(form.get("passwordConfirmation") ?? "");
  if (name === "" || password === "") {
    return { error: "이름과 비밀번호를 입력해 주세요" };
  }
  if (password !== confirmation) {
    return { error: "비밀번호가 서로 다릅니다" };
  }
  let session: { token: string; user: AuthenticatedUser } | null;
  try {
    const user = await apiSend<UserSummary>("POST", "/api/users", { inviteToken: token, name, password });
    session = await apiSend<{ token: string; user: AuthenticatedUser }>("POST", "/api/sessions", {
      email: user!.email,
      password,
    });
  } catch (error) {
    return { error: message(error) };
  }
  await setSessionCookie(session!.token);
  redirect("/");
}

/** 재설정 링크로 새 비밀번호 설정. 기존 세션은 모두 끊기므로 로그인 화면으로 */
export async function resetPassword(token: string, _: ActionState, form: FormData): Promise<ActionState> {
  const password = String(form.get("password") ?? "");
  const confirmation = String(form.get("passwordConfirmation") ?? "");
  if (password === "") {
    return { error: "새 비밀번호를 입력해 주세요" };
  }
  if (password !== confirmation) {
    return { error: "비밀번호가 서로 다릅니다" };
  }
  try {
    // 링크의 사용자를 서버에서 다시 확인 (화면이 보낸 값에 기대지 않음)
    const reset = await apiGet<PasswordResetLinkInfo>(`/api/password-resets/${encodeURIComponent(token)}`);
    await apiSend("PUT", `/api/users/${reset.user.id}/password`, { resetToken: token, password });
  } catch (error) {
    return { error: message(error) };
  }
  redirect("/login?changed=1");
}

/** Spring 오류를 화면 문구로. 링크 만료·사용·위조는 서버가 모두 404로 응답 */
function message(error: unknown): string {
  if (isApiError(error)) {
    switch (error.status) {
      case 400:
        return error.detail.includes("at least")
          ? "비밀번호는 10자 이상이어야 합니다"
          : `입력값을 확인해 주세요: ${error.detail}`;
      case 403:
        return "권한이 없습니다. 관리자만 사용할 수 있습니다";
      case 404:
        return "링크가 만료되었거나 이미 사용되었습니다. 새 링크를 요청해 주세요";
      case 409:
        return "이미 사용 중인 이메일입니다";
    }
  }
  console.error("user action failed", error);
  return "요청을 처리하지 못했습니다. API 서버 상태를 확인해 주세요";
}
