"use server";

import { refresh } from "next/cache";

import { apiSend, isApiError } from "@/lib/api/server";
import type { IssuedAccessToken } from "@/lib/api/types";
import { VALIDITY_DAYS } from "@/lib/access-token";

/**
 * API 토큰 발급·폐기. 토큰 원문은 발급 응답에서만 확인할 수 있어 화면에 한 번만 표시
 */

export type ActionState = { error?: string } | undefined;

export type IssueState = { token?: string; expiresAt?: string; error?: string } | undefined;

export async function issueAccessToken(_: IssueState, form: FormData): Promise<IssueState> {
  const name = String(form.get("name") ?? "").trim();
  const validityDays = Number(form.get("validityDays"));
  if (name === "") {
    return { error: "토큰 이름(용도)을 입력해 주세요" };
  }
  if (!(VALIDITY_DAYS as readonly number[]).includes(validityDays)) {
    return { error: "유효 기간을 선택해 주세요" };
  }
  let issued: IssuedAccessToken | null;
  try {
    issued = await apiSend<IssuedAccessToken>("POST", "/api/access-tokens", { name, validityDays });
  } catch (error) {
    return { error: message(error) };
  }
  refresh();
  return { token: issued!.token, expiresAt: issued!.accessToken.expiresAt };
}

export async function revokeAccessToken(tokenId: number): Promise<ActionState> {
  try {
    await apiSend("DELETE", `/api/access-tokens/${Number(tokenId)}`);
  } catch (error) {
    if (isApiError(error) && error.status === 404) {
      refresh();
      return { error: "이미 폐기된 토큰입니다" };
    }
    return { error: message(error) };
  }
  refresh();
  return {};
}

function message(error: unknown): string {
  if (isApiError(error) && error.status === 400) {
    return `입력값을 확인해 주세요: ${error.detail}`;
  }
  console.error("access token action failed", error);
  return "요청을 처리하지 못했습니다. API 서버 상태를 확인해 주세요";
}
