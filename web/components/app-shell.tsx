import { KeyRound } from "lucide-react";
import Link from "next/link";

import { LogoutButton } from "@/components/auth/logout-button";
import { Brand, MobileNav, SidebarNav } from "@/components/sidebar-nav";
import { ThemeToggle } from "@/components/theme-toggle";
import { apiFetch } from "@/lib/api/server";
import { sessionToken } from "@/lib/api/session";
import type { AuthenticatedUser } from "@/lib/api/types";
import type { Theme } from "@/lib/theme";
import { themeFromCookie } from "@/lib/theme-cookie";

/**
 * 화면 틀 (DESIGN.md "배치")
 * - 로그인 상태: 짙은 보라 사이드바 + 흰 본문. 좁은 화면(md 미만)은 위쪽 막대의 메뉴 서랍
 * - 로그인 전(로그인·초대·재설정 화면): 사이드바 없이 짙은 보라 바탕 가운데 흰 카드
 */
export async function AppShell({ children }: { children: React.ReactNode }) {
  const user = await currentUser();

  if (!user) {
    return (
      <main className="flex flex-1 flex-col items-center bg-sidebar px-4 py-12 sm:py-20">
        <p className="mb-6 flex items-center gap-2 text-lg font-semibold tracking-tight text-white">
          <span aria-hidden className="size-2 rounded-full bg-sidebar-primary" />
          Sentinel
        </p>
        <div className="w-full max-w-sm rounded-xl bg-background p-6 shadow-[0_10px_15px_-3px_rgb(0_0_0/0.25)] sm:p-8">
          {children}
        </div>
      </main>
    );
  }

  const isAdmin = user.role === "ADMIN";
  const account = <AccountFooter email={user.email} theme={await themeFromCookie()} />;
  return (
    <div className="flex flex-1">
      {/* 바탕 칸은 본문 길이만큼 늘어나고, 메뉴는 화면 높이에 고정 */}
      <div className="hidden w-56 shrink-0 bg-sidebar md:block">
        <aside aria-label="사이드바" className="sticky top-0 flex h-svh flex-col py-4 text-sidebar-foreground">
          <Brand />
          <SidebarNav isAdmin={isAdmin} />
          <div className="mt-auto">{account}</div>
        </aside>
      </div>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-12 items-center justify-between bg-sidebar px-3 md:hidden">
          <Link href="/" className="flex items-center gap-2 px-2 font-semibold tracking-tight text-white">
            <span aria-hidden className="size-2 rounded-full bg-sidebar-primary" />
            Sentinel
          </Link>
          <MobileNav isAdmin={isAdmin} footer={account} />
        </header>
        <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 sm:px-8 sm:py-8">{children}</main>
      </div>
    </div>
  );
}

/** 사이드바 아래 계정 영역: 테마 선택, 비밀번호 변경 화면으로 가는 계정 링크, 로그아웃 */
function AccountFooter({ email, theme }: { email: string; theme: Theme }) {
  return (
    <div>
      <ThemeToggle initial={theme} />
      <div className="mx-3 space-y-0.5 border-t border-sidebar-border pt-3">
        <Link
          href="/settings/password"
          title="비밀번호 변경"
          className="flex items-center gap-2.5 rounded-md px-3 py-2 text-sm transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
        >
          <KeyRound aria-hidden className="size-4 shrink-0" />
          <span className="truncate">{email}</span>
        </Link>
        <LogoutButton />
      </div>
    </div>
  );
}

/**
 * 로그인 화면에서도 렌더링되므로 세션이 없으면 API를 호출하지 않음.
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
