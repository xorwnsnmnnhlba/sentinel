import Link from "next/link";

import { Button } from "@/components/ui/button";

export default function SuiteNotFound() {
  return (
    <div className="space-y-4 py-16 text-center">
      <h1 className="text-xl font-semibold">스위트를 찾을 수 없습니다</h1>
      <p className="text-sm text-muted-foreground">삭제되었거나 존재하지 않는 스위트입니다</p>
      <Button asChild variant="outline">
        <Link href="/projects">프로젝트 목록으로</Link>
      </Button>
    </div>
  );
}
