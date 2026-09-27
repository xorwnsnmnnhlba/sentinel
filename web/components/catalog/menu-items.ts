import type { ActionMenuItem } from "@/components/catalog/action-menu";

/**
 * 자주 쓰는 "⋯" 메뉴 구성. 서버 컴포넌트에서 쓰므로 "use client" 모듈(action-menu)과 분리
 * (클라이언트 모듈의 값은 서버에서 참조로만 전달되어 배열로 읽을 수 없음)
 */
export const EDIT_DELETE: ActionMenuItem[] = [
  { id: "edit", label: "수정", icon: "edit" },
  { id: "delete", label: "삭제", icon: "delete", destructive: true },
];

/** 환경 행 메뉴: 수정 · 복제(원본 값을 채운 등록 대화상자) · 삭제 */
export const EDIT_DUPLICATE_DELETE: ActionMenuItem[] = [
  { id: "edit", label: "수정", icon: "edit" },
  { id: "duplicate", label: "복제", icon: "copy" },
  { id: "delete", label: "삭제", icon: "delete", destructive: true },
];

/**
 * 케이스 행 메뉴: 실행(환경이 있을 때) · 수정(별도 화면) · 복제 · 삭제
 * @param duplicate 복제 Server Action (대화상자 없이 바로 실행)
 */
export function caseMenu(
  caseId: number,
  { runnable, duplicate }: { runnable: boolean; duplicate: ActionMenuItem["action"] },
): ActionMenuItem[] {
  return [
    ...(runnable ? [{ id: "run", label: "실행", icon: "run" } as const] : []),
    { id: "edit", label: "수정", icon: "edit", href: `/cases/${caseId}` },
    { id: "duplicate", label: "복제", icon: "copy", action: duplicate },
    { id: "delete", label: "삭제", icon: "delete", destructive: true },
  ];
}
