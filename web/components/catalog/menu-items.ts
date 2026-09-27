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
 * "변경 이력" 항목 추가. 되돌릴 수 없는 동작(삭제) 앞에 두어 구분선 아래에는 삭제만 남김
 * @param href 변경 이력 화면 주소 (lib/change-log.ts historyHref·projectHistoryHref)
 */
export function withHistory(items: ActionMenuItem[], href: string): ActionMenuItem[] {
  const history: ActionMenuItem = { id: "history", label: "변경 이력", icon: "history", href };
  const destructive = items.findIndex((item) => item.destructive);
  return destructive < 0 ? [...items, history] : [...items.slice(0, destructive), history, ...items.slice(destructive)];
}

/**
 * 케이스 행 메뉴: 실행·여기까지 실행(환경이 있을 때) · 수정(별도 화면) · 복제 · 삭제
 * @param first 스위트의 첫 케이스. 여기까지 실행이 단건 실행과 같으므로 제외
 * @param duplicate 복제 Server Action (대화상자 없이 바로 실행)
 */
export function caseMenu(
  caseId: number,
  { runnable, first, duplicate }: { runnable: boolean; first: boolean; duplicate: ActionMenuItem["action"] },
): ActionMenuItem[] {
  return [
    ...(runnable ? [{ id: "run", label: "실행", icon: "run" } as const] : []),
    ...(runnable && !first ? [{ id: "run-through", label: "여기까지 실행", icon: "run" } as const] : []),
    { id: "edit", label: "수정", icon: "edit", href: `/cases/${caseId}` },
    { id: "duplicate", label: "복제", icon: "copy", action: duplicate },
    { id: "delete", label: "삭제", icon: "delete", destructive: true },
  ];
}
