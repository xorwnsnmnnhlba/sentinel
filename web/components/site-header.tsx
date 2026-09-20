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
      <div className="mx-auto flex h-14 w-full max-w-6xl items-center gap-6 px-6">
        <Link href="/" className="font-semibold tracking-tight">
          Sentinel
        </Link>
        {user && (
          <nav className="flex items-center gap-4 text-sm text-muted-foreground">
            {navigation.map((item) => (
              <Link key={item.href} href={item.href} className="transition-colors hover:text-foreground">
                {item.label}
              </Link>
            ))}
          </nav>
        )}
        {user && (
          <div className="ml-auto flex items-center gap-2 text-sm text-muted-foreground">
            <span>{user.email}</span>
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
