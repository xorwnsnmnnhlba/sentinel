"use client";

import { LogOut } from "lucide-react";
import { useTransition } from "react";

import { Button } from "@/components/ui/button";
import { logout } from "@/lib/actions/auth";

export function LogoutButton() {
  const [pending, startTransition] = useTransition();

  return (
    <Button
      variant="ghost"
      size="sm"
      disabled={pending}
      onClick={() => startTransition(async () => logout())}
      aria-label="로그아웃"
    >
      <LogOut /> 로그아웃
    </Button>
  );
}
