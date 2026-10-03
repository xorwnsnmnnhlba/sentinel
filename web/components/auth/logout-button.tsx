"use client";

import { LogOut } from "lucide-react";
import { useTransition } from "react";

import { logout } from "@/lib/actions/auth";

/** 사이드바 계정 영역의 로그아웃. 메뉴 항목과 같은 모양 */
export function LogoutButton() {
  const [pending, startTransition] = useTransition();

  return (
    <button
      type="button"
      disabled={pending}
      onClick={() => startTransition(async () => logout())}
      className="flex w-full items-center gap-2.5 rounded-sm px-3 py-1.5 text-sm transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground disabled:opacity-60"
    >
      <LogOut aria-hidden className="size-4" />
      로그아웃
    </button>
  );
}
