import type { ChangeAction, ChangeLogItem, ChangeTargetType } from "@/lib/api/types";

/** 변경 이력 표시 문구와 링크 (api/README.md "변경 이력") */

export const TARGET_TYPES: ChangeTargetType[] = [
  "PROJECT",
  "ENVIRONMENT",
  "SUITE",
  "CASE",
  "SCHEDULE",
  "USER",
  "INVITE",
];

export const TARGET_LABELS: Record<ChangeTargetType, string> = {
  PROJECT: "프로젝트",
  ENVIRONMENT: "환경",
  SUITE: "스위트",
  CASE: "케이스",
  SCHEDULE: "스케줄",
  USER: "사용자",
  INVITE: "초대",
};

const ACTION_LABELS: Record<ChangeAction, string> = { CREATED: "등록", UPDATED: "수정", DELETED: "삭제" };

/** 동작 이름. 사용자 등록은 가입, 초대 등록은 초대 발급 */
export function actionLabel(item: Pick<ChangeLogItem, "targetType" | "action">): string {
  if (item.action === "CREATED" && item.targetType === "USER") {
    return "가입";
  }
  if (item.action === "CREATED" && item.targetType === "INVITE") {
    return "발급";
  }
  return ACTION_LABELS[item.action];
}

const FIELD_LABELS: Record<string, string> = {
  name: "이름",
  description: "설명",
  baseUrl: "기본 URL",
  request: "요청",
  assertions: "판정 규칙",
  extractors: "값 추출",
  caseOrder: "실행 순서",
  environment: "실행 환경",
  cron: "실행 주기(cron)",
  enabled: "활성 여부",
  password: "비밀번호",
  passwordResetLink: "비밀번호 재설정 링크 발급",
};

/** 바뀐 항목 이름. variables.키·secrets.이름은 키를 함께 표시, 모르는 이름은 그대로 */
export function fieldLabel(field: string): string {
  if (field.startsWith("variables.")) {
    return `변수 ${field.slice("variables.".length)}`;
  }
  if (field.startsWith("secrets.")) {
    return `시크릿 ${field.slice("secrets.".length)}`;
  }
  return FIELD_LABELS[field] ?? field;
}

/**
 * 대상 화면 주소. 삭제 기록과 화면이 없는 대상(스케줄·사용자·초대)은 null
 * 환경은 소속 프로젝트 화면
 */
export function targetHref(item: ChangeLogItem): string | null {
  if (item.action === "DELETED") {
    return null;
  }
  switch (item.targetType) {
    case "PROJECT":
      return `/projects/${item.targetId}`;
    case "SUITE":
      return `/suites/${item.targetId}`;
    case "CASE":
      return `/cases/${item.targetId}`;
    case "ENVIRONMENT":
      return item.projectId === null ? null : `/projects/${item.projectId}`;
    default:
      return null;
  }
}

/** 항목 1건의 변경 이력 화면 주소 */
export function historyHref(targetType: ChangeTargetType, targetId: number): string {
  return `/change-logs?${new URLSearchParams({ targetType, targetId: String(targetId) })}`;
}

/** 프로젝트와 그 안의 항목 변경 이력 화면 주소 */
export function projectHistoryHref(projectId: number): string {
  return `/change-logs?${new URLSearchParams({ projectId: String(projectId) })}`;
}

export function isTargetType(value: string | undefined): value is ChangeTargetType {
  return value !== undefined && (TARGET_TYPES as string[]).includes(value);
}
