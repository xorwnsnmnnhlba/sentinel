import Link from "next/link";

import { Button } from "@/components/ui/button";

export default function RunNotFound() {
  return (
    <div className="space-y-4 py-16 text-center">
      <h1 className="text-xl font-semibold">실행을 찾을 수 없습니다</h1>
      <p className="text-sm text-muted-foreground">삭제되었거나 존재하지 않는 실행 번호입니다</p>
      <Button asChild variant="outline">
        <Link href="/runs">실행 이력으로</Link>
      </Button>
    </div>
  );
}
