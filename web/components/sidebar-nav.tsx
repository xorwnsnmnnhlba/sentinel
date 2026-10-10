"use client";

import {
  BellRing,
  CalendarClock,
  CalendarOff,
  FileClock,
  FolderKanban,
  History,
  LayoutDashboard,
  Menu,
  Search,
  ServerCog,
  UsersRound,
} from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetDescription, SheetTitle, SheetTrigger } from "@/components/ui/sheet";

/** 화면 추가 시 항목 추가. match는 함께 강조할 하위 경로 (스위트·케이스는 프로젝트 아래 화면) */
const navigation = [
  { href: "/", label: "홈", icon: LayoutDashboard, match: [] as string[] },
  { href: "/projects", label: "프로젝트", icon: FolderKanban, match: ["/suites", "/cases"] },
  { href: "/search", label: "케이스 검색", icon: Search, match: [] as string[] },
  { href: "/runs", label: "실행 이력", icon: History, match: [] as string[] },
  { href: "/schedules", label: "스케줄", icon: CalendarClock, match: [] as string[] },
  { href: "/change-logs", label: "변경 이력", icon: FileClock, match: [] as string[] },
  { href: "/notifications", label: "알림 이력", icon: BellRing, match: [] as string[] },
  { href: "/holidays", label: "휴일", icon: CalendarOff, match: [] as string[] },
];

const adminNavigation = [
  { href: "/users", label: "사용자", icon: UsersRound, match: [] as string[] },
  { href: "/system", label: "시스템 상태", icon: ServerCog, match: [] as string[] },
];

/** 현재 화면에 해당하는 메뉴 주소. 해당 메뉴가 없으면(비밀번호 변경 등) undefined */
export function activeHref(pathname: string): string | undefined {
  return [...navigation, ...adminNavigation].find((item) => isActive(pathname, item))?.href;
}

function isActive(pathname: string, item: { href: string; match: string[] }): boolean {
  if (item.href === "/") {
    return pathname === "/";
  }
  return [item.href, ...item.match].some((prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`));
}

/** 사이드바 주요 메뉴. 현재 화면은 aria-current와 흰 표시선으로 구분 */
export function SidebarNav({ isAdmin, onNavigate }: { isAdmin: boolean; onNavigate?: () => void }) {
  const current = activeHref(usePathname());
  const items = isAdmin ? [...navigation, ...adminNavigation] : navigation;

  return (
    <nav aria-label="주요 메뉴" className="flex flex-col gap-0.5 px-3">
      {items.map((item) => {
        const active = item.href === current;
        return (
          <Link
            key={item.href}
            href={item.href}
            onClick={onNavigate}
            aria-current={active ? "page" : undefined}
            className="flex items-center gap-2.5 rounded-sm px-3 py-1.5 text-sm transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground aria-[current=page]:bg-sidebar-accent aria-[current=page]:text-sidebar-accent-foreground aria-[current=page]:shadow-[inset_2px_0_0_var(--sidebar-primary)]"
          >
            <item.icon aria-hidden className="size-4" />
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}

/** 좁은 화면의 메뉴 서랍. 사이드바 내용을 그대로 표시하고 메뉴를 고르면 닫음 */
export function MobileNav({ isAdmin, footer }: { isAdmin: boolean; footer: React.ReactNode }) {
  const [open, setOpen] = useState(false);

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          aria-label="메뉴 열기"
          className="text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
        >
          <Menu />
        </Button>
      </SheetTrigger>
      <SheetContent side="left" className="w-64 gap-0 border-sidebar-border bg-sidebar p-0 text-sidebar-foreground">
        <SheetTitle className="sr-only">메뉴</SheetTitle>
        <SheetDescription className="sr-only">Sentinel 화면 이동</SheetDescription>
        <div className="flex h-full flex-col py-4">
          <Brand />
          <SidebarNav isAdmin={isAdmin} onNavigate={() => setOpen(false)} />
          <div className="mt-auto">{footer}</div>
        </div>
      </SheetContent>
    </Sheet>
  );
}

export function Brand() {
  return (
    <Link href="/" className="mb-5 block px-6 py-1">
      <Wordmark />
    </Link>
  );
}

/** 이름 표기. 고정폭 대문자(CSS), 장식 없음. 읽기 도구에는 "Sentinel" */
export function Wordmark() {
  return <span className="font-mono text-sm font-semibold tracking-[0.2em] text-white uppercase">Sentinel</span>;
}
