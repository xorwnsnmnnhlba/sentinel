"use client";

import { Copy, Ellipsis, History, KeyRound, Pencil, Play, Trash2 } from "lucide-react";
import Link from "next/link";
import { createContext, use, useRef, useState, useTransition } from "react";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

const icons = { edit: Pencil, delete: Trash2, key: KeyRound, run: Play, copy: Copy, history: History };

export type ActionMenuItem = {
  /** 대화상자와 연결하는 이름. 같은 메뉴 안의 대화상자는 menuId로 이 값을 지정 */
  id: string;
  label: string;
  icon: keyof typeof icons;
  /** 지정 시 대화상자 대신 화면 이동 */
  href?: string;
  /** 지정 시 대화상자 없이 바로 실행하는 Server Action (예: 복제). 실패하면 사유를 대화상자로 표시 */
  action?: () => Promise<{ error?: string } | undefined | void>;
  /** 삭제 등 되돌릴 수 없는 동작. 구분선 아래 빨간 글자 */
  destructive?: boolean;
};

type MenuState = {
  openId: string | null;
  setOpenId: (id: string | null) => void;
  returnFocus: (event: Event) => void;
};

const MenuContext = createContext<MenuState | null>(null);

/**
 * "⋯" 메뉴 (DESIGN.md "버튼 배치"). 수정·삭제 등 부수 동작을 한곳에 모음
 *
 * 메뉴 항목을 고르면 메뉴는 닫히고 같은 id의 대화상자가 열림. 대화상자를 메뉴 안에 두면 메뉴가 닫힐 때 함께 사라지므로
 * 대화상자는 children으로 메뉴 밖에 두고 열림 상태만 공유. 대화상자를 닫으면 포커스를 "⋯" 버튼으로 되돌림
 */
export function ActionMenu({
  label,
  items,
  size = "row",
  children,
}: {
  /** 접근 이름. 대상을 포함 (예: "환경 self 작업") */
  label: string;
  items: ActionMenuItem[];
  /** row: 표의 행 끝, page: 화면 머리 */
  size?: "row" | "page";
  children?: React.ReactNode;
}) {
  const [openId, setOpenId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string>();
  const [pending, startTransition] = useTransition();
  const trigger = useRef<HTMLButtonElement>(null);

  function select(item: ActionMenuItem) {
    const action = item.action;
    if (!action) {
      setOpenId(item.id);
      return;
    }
    startTransition(async () => {
      const result = await action();
      if (result?.error) {
        setActionError(result.error);
      }
    });
  }

  function returnFocus(event: Event) {
    event.preventDefault();
    trigger.current?.focus();
  }

  return (
    <MenuContext value={{ openId, setOpenId, returnFocus }}>
      {/* 모달이 아니어야 메뉴가 닫히면서 여는 대화상자와 포커스·포인터 잠금이 겹치지 않음 */}
      <DropdownMenu modal={false}>
        <DropdownMenuTrigger asChild>
          <Button
            ref={trigger}
            variant={size === "page" ? "outline" : "ghost"}
            size={size === "page" ? "icon" : "icon-sm"}
            aria-label={label}
            disabled={pending}
            aria-busy={pending}
          >
            <Ellipsis />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-36">
          {items.map((item, index) => {
            const Icon = icons[item.icon];
            return (
              <MenuEntry key={item.id} separated={item.destructive === true && index > 0}>
                {item.href ? (
                  <DropdownMenuItem asChild>
                    <Link href={item.href}>
                      <Icon aria-hidden />
                      {item.label}
                    </Link>
                  </DropdownMenuItem>
                ) : (
                  <DropdownMenuItem
                    variant={item.destructive ? "destructive" : "default"}
                    onSelect={() => select(item)}
                  >
                    <Icon aria-hidden />
                    {item.label}
                  </DropdownMenuItem>
                )}
              </MenuEntry>
            );
          })}
        </DropdownMenuContent>
      </DropdownMenu>
      {children}
      <AlertDialog open={actionError !== undefined} onOpenChange={(open) => !open && setActionError(undefined)}>
        <AlertDialogContent onCloseAutoFocus={returnFocus}>
          <AlertDialogHeader>
            <AlertDialogTitle>작업을 완료하지 못했습니다</AlertDialogTitle>
            <AlertDialogDescription>{actionError}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogAction>확인</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </MenuContext>
  );
}

function MenuEntry({ separated, children }: { separated: boolean; children: React.ReactNode }) {
  return (
    <>
      {separated && <DropdownMenuSeparator />}
      {children}
    </>
  );
}

/**
 * 메뉴와 연결된 대화상자의 열림 상태. menuId가 없거나 메뉴 밖이면 null (대화상자가 직접 상태 관리)
 */
export function useMenuDialog(menuId: string | undefined) {
  const menu = use(MenuContext);
  if (!menuId || !menu) {
    return null;
  }
  return {
    open: menu.openId === menuId,
    setOpen: (open: boolean) => menu.setOpenId(open ? menuId : null),
    returnFocus: menu.returnFocus,
  };
}
