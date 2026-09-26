"use client";

import { Monitor, Moon, Sun } from "lucide-react";
import { useState, useTransition } from "react";

import { setTheme } from "@/lib/actions/theme";
import type { Theme } from "@/lib/theme";

const options = [
  { theme: "system", label: "시스템 설정", icon: Monitor },
  { theme: "light", label: "라이트", icon: Sun },
  { theme: "dark", label: "다크", icon: Moon },
] as const satisfies { theme: Theme; label: string; icon: unknown }[];

/**
 * 테마 선택 (사이드바 계정 영역). 누르면 화면을 바로 바꾸고 쿠키에 저장하여 다음 요청부터 서버도 같은 테마로 렌더링
 */
export function ThemeToggle({ initial }: { initial: Theme }) {
  const [theme, setCurrent] = useState(initial);
  const [, startTransition] = useTransition();

  function choose(next: Theme) {
    setCurrent(next);
    document.documentElement.setAttribute("data-theme", next);
    startTransition(() => setTheme(next));
  }

  return (
    <div role="group" aria-label="테마" className="mx-3 mb-2 flex gap-0.5 rounded-md bg-sidebar-accent p-0.5">
      {options.map((option) => (
        <button
          key={option.theme}
          type="button"
          aria-pressed={theme === option.theme}
          aria-label={option.label}
          title={option.label}
          onClick={() => choose(option.theme)}
          className="flex flex-1 items-center justify-center rounded-[5px] py-1.5 text-sidebar-foreground transition-colors hover:text-sidebar-accent-foreground aria-pressed:bg-sidebar aria-pressed:text-sidebar-accent-foreground"
        >
          <option.icon aria-hidden className="size-3.5" />
        </button>
      ))}
    </div>
  );
}
