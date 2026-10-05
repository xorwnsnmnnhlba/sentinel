"use server";

import { refresh } from "next/cache";

import { apiSend, isApiError } from "@/lib/api/server";
import { holidayError } from "@/lib/holiday";

/**
 * 휴일 등록·삭제. 모든 스케줄에 영향을 주므로 관리자만 (그 외는 Spring이 403)
 * Server Action은 화면을 거치지 않고 직접 호출 가능하므로 입력값을 다시 확인
 */

export type ActionState = { error?: string } | undefined;

export async function createHoliday(_: ActionState, form: FormData): Promise<ActionState> {
  const date = String(form.get("date") ?? "");
  const name = String(form.get("name") ?? "");
  const error = holidayError(date, name);
  if (error) {
    return { error };
  }
  try {
    await apiSend("POST", "/api/holidays", { date, name: name.trim() });
  } catch (error) {
    if (isApiError(error) && error.status === 409) {
      return { error: `${date}은(는) 이미 휴일로 등록되어 있습니다` };
    }
    return { error: message(error) };
  }
  refresh();
  return {};
}

/** 삭제하면 그날도 스케줄 실행 */
export async function deleteHoliday(holidayId: number): Promise<ActionState> {
  if (!Number.isSafeInteger(holidayId) || holidayId <= 0) {
    return { error: "요청 형식이 올바르지 않습니다" };
  }
  try {
    await apiSend("DELETE", `/api/holidays/${holidayId}`);
  } catch (error) {
    if (isApiError(error) && error.status === 404) {
      refresh();
      return { error: "이미 삭제된 휴일입니다" };
    }
    return { error: message(error) };
  }
  refresh();
  return {};
}

function message(error: unknown): string {
  if (isApiError(error)) {
    switch (error.status) {
      case 400:
        return `입력값을 확인해 주세요: ${error.detail}`;
      case 403:
        return "휴일은 관리자만 등록·삭제할 수 있습니다";
    }
  }
  console.error("holiday action failed", error);
  return "요청을 처리하지 못했습니다. API 서버 상태를 확인해 주세요";
}
