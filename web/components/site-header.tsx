import { UserRound } from "lucide-react";
import Link from "next/link";

import { LogoutButton } from "@/components/auth/logout-button";
import { Separator } from "@/components/ui/separator";
import { apiFetch } from "@/lib/api/server";
import { sessionToken } from "@/lib/api/session";
import type { AuthenticatedUser } from "@/lib/api/types";

/** 화면 추가 시 항목 추가 */
const navigation = [
  { href: "/", label: "홈" },
  { href: "/projects", label: "프로젝트" },
  { href: "/runs", label: "실행 이력" },
];

export async function SiteHeader() {
  const user = await currentUser();

  return (
    <header className="bg-background">
      {/*
        좁은 화면: 1행 로고·계정, 2행 메뉴 (메뉴가 넘치면 메뉴만 가로 스크롤). 좌우 여백은 본문(main px-6)과 맞춤
        sm 이상: 한 줄 (로고 · 메뉴 · 계정)
        읽는 순서는 넓은 화면 기준(로고 → 메뉴 → 계정)으로 두고, 좁은 화면에서는 메뉴만 다음 줄로 배치
      */}
      <div className="mx-auto flex w-full max-w-6xl flex-wrap items-center gap-x-6 px-6 py-2 sm:h-14 sm:flex-nowrap sm:py-0">
        <Link href="/" className="shrink-0 py-1.5 font-semibold tracking-tight sm:py-0">
          Sentinel
        </Link>
        {user && (
          <nav
            aria-label="주요 메뉴"
            className="order-last -mx-6 flex w-[calc(100%+3rem)] gap-4 overflow-x-auto px-6 py-1.5 text-sm whitespace-nowrap text-muted-foreground sm:order-none sm:mx-0 sm:w-auto sm:overflow-visible sm:px-0 sm:py-0"
          >
            {navigation.map((item) => (
              <Link key={item.href} href={item.href} className="transition-colors hover:text-foreground">
                {item.label}
              </Link>
            ))}
            {user.role === "ADMIN" && (
              <Link href="/users" className="transition-colors hover:text-foreground">
                사용자
              </Link>
            )}
          </nav>
        )}
        {user && (
          <div className="ml-auto flex shrink-0 items-center gap-1 text-sm text-muted-foreground sm:gap-2">
            <Link
              href="/settings/password"
              title={user.email}
              className="flex items-center gap-1.5 rounded-md p-1.5 transition-colors hover:text-foreground sm:p-0"
            >
              <UserRound aria-hidden className="size-4 sm:hidden" />
              {/* 좁은 화면에서는 아이콘만 보이고 이메일은 화면 낭독기용으로 유지 */}
              <span className="sr-only sm:not-sr-only">{user.email}</span>
            </Link>
            <LogoutButton />
          </div>
        )}
      </div>
      <Separator />
    </header>
  );
}

/**
 * 머리말은 로그인 화면에서도 렌더링되므로 세션이 없으면 API를 호출하지 않음.
 * 만료된 세션이면 사용자 없이 표시하고, 화면 이동은 proxy·페이지 조회가 처리
 */
async function currentUser(): Promise<AuthenticatedUser | null> {
  if (!(await sessionToken())) {
    return null;
  }
  try {
    const response = await apiFetch("/api/sessions/current", { headers: { Accept: "application/json" } });
    return response.ok ? ((await response.json()) as AuthenticatedUser) : null;
  } catch {
    return null;
  }
}
