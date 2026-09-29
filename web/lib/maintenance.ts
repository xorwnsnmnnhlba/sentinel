import type { MaintenanceWindow } from "@/lib/api/types";

/**
 * 점검 시간 입력·표시 (api/README.md "점검 시간"). 화면의 다른 시각과 같이 KST 기준.
 * 한국은 일광 절약 시간이 없어 +09:00 고정
 */

export const MAX_DAYS = 7;

export const MAX_REASON_LENGTH = 200;

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** datetime-local 입력값(YYYY-MM-DDTHH:mm, KST) */
export function toKstInput(date: Date): string {
  return new Date(date.getTime() + KST_OFFSET_MS).toISOString().slice(0, 16);
}

/**
 * datetime-local 입력값(KST)을 ISO-8601 시각으로. 형식이 다르거나 없는 날짜면 null
 * (다시 입력값으로 바꿔 같은지로 확인. 형식이 다르거나 2월 30일처럼 넘어간 날짜는 달라짐)
 */
export function fromKstInput(value: string): string | null {
  const date = new Date(`${value}:00+09:00`);
  return Number.isNaN(date.getTime()) || toKstInput(date) !== value ? null : date.toISOString();
}

/** 서버와 같은 기준으로 먼저 확인하여 한국어로 안내. 문제가 없으면 null */
export function periodError(startsAt: string, endsAt: string, now: Date): string | null {
  const start = Date.parse(startsAt);
  const end = Date.parse(endsAt);
  if (end <= start) {
    return "끝 시각은 시작 시각보다 뒤여야 합니다";
  }
  if (end <= now.getTime()) {
    return "끝 시각이 이미 지났습니다";
  }
  if (end - start > MAX_DAYS * 24 * 60 * 60 * 1000) {
    return `점검 시간은 최대 ${MAX_DAYS}일입니다`;
  }
  return null;
}

/** 기간 문구 (KST). 같은 날이면 끝은 시각만. 예: "09-29 10:00 ~ 11:00", "09-29 22:00 ~ 09-30 02:00" */
export function formatPeriod(startsAt: string, endsAt: string): string {
  const start = toKstInput(new Date(startsAt));
  const end = toKstInput(new Date(endsAt));
  const short = (input: string) => `${input.slice(5, 10)} ${input.slice(11)}`;
  return `${short(start)} ~ ${start.slice(0, 10) === end.slice(0, 10) ? end.slice(11) : short(end)}`;
}

/** 끝 시각 문구 (KST). 오늘이면 시각만, 아니면 날짜 포함. 예: "11:00", "09-30 02:00" */
export function formatEnd(endsAt: string, now: Date): string {
  const end = toKstInput(new Date(endsAt));
  return end.slice(0, 10) === toKstInput(now).slice(0, 10) ? end.slice(11) : `${end.slice(5, 10)} ${end.slice(11)}`;
}

/** 환경의 점검 중 우선, 없으면 가장 먼저 시작하는 예정 점검 (목록은 시작 순) */
export function maintenanceOf(windows: MaintenanceWindow[], environmentId: number): MaintenanceWindow | undefined {
  const own = windows.filter((window) => window.environment.id === environmentId);
  return own.find((window) => window.active) ?? own[0];
}
