import Link from "next/link";

import { Separator } from "@/components/ui/separator";

/** 화면 추가 시 항목 추가 */
const navigation = [
  { href: "/", label: "홈" },
  { href: "/projects", label: "프로젝트" },
  { href: "/runs", label: "실행 이력" },
];

export function SiteHeader() {
  return (
    <header className="bg-background">
      <div className="mx-auto flex h-14 w-full max-w-6xl items-center gap-6 px-6">
        <Link href="/" className="font-semibold tracking-tight">
          Sentinel
        </Link>
        <nav className="flex items-center gap-4 text-sm text-muted-foreground">
          {navigation.map((item) => (
            <Link key={item.href} href={item.href} className="transition-colors hover:text-foreground">
              {item.label}
            </Link>
          ))}
        </nav>
      </div>
      <Separator />
    </header>
  );
}
