import type { ActionMenuItem } from "@/components/catalog/action-menu";

/**
 * 자주 쓰는 "⋯" 메뉴 구성. 서버 컴포넌트에서 쓰므로 "use client" 모듈(action-menu)과 분리
 * (클라이언트 모듈의 값은 서버에서 참조로만 전달되어 배열로 읽을 수 없음)
 */
export const EDIT_DELETE: ActionMenuItem[] = [
  { id: "edit", label: "수정", icon: "edit" },
  { id: "delete", label: "삭제", icon: "delete", destructive: true },
];

/** 수정은 별도 화면인 경우 (케이스) */
export function editLinkDelete(href: string): ActionMenuItem[] {
  return [
    { id: "edit", label: "수정", icon: "edit", href },
    { id: "delete", label: "삭제", icon: "delete", destructive: true },
  ];
}
