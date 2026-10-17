import type { FailureCause, FailureCauseCase, FailureCauseGroup } from "@/lib/api/types";

/** 실패 원인 표시 문구 (api/README.md "실패 원인") */

export const CAUSE_LABELS: Record<FailureCause, string> = {
  TIMEOUT: "타임아웃",
  CONNECTION_FAILED: "연결 실패",
  CONNECTION_LOST: "연결 끊김",
  TLS: "인증서·TLS 오류",
  SERVER_ERROR: "서버 오류 5xx",
  CLIENT_ERROR: "요청 거부 4xx",
  ASSERTION_MISMATCH: "응답 내용 불일치",
  CONFIGURATION: "설정 오류",
  OTHER: "기타",
};

/** 원인 묶음 = 먼저 볼 곳 */
export const GROUP_LABELS: Record<FailureCauseGroup, string> = {
  NETWORK: "GW·네트워크",
  API: "API",
  SETUP: "케이스·환경 설정",
  OTHER: "Sentinel",
};

/** 전체 실패 대비 비율. 예: 12/30 → "40%" (반올림, 0건이면 "0%") */
export function formatShare(results: number, total: number): string {
  return total === 0 ? "0%" : `${Math.round((results / total) * 100)}%`;
}

/** 그 케이스·환경의 기간 안 실패 실행 목록 */
export function failingRunsHref(item: FailureCauseCase, from: string, to: string): string {
  return `/runs?suiteId=${item.suite.id}&environmentId=${item.environment.id}&failing=true&from=${from}&to=${to}`;
}

/** 대표 케이스 뒤에 붙는 나머지 수. 예: 케이스·환경 5개 중 1개 표시 → " 외 4" */
export function moreCases(caseCount: number, shown: number): string {
  return caseCount > shown ? ` 외 ${caseCount - shown}` : "";
}
