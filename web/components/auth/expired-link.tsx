import Link from "next/link";

import { Button } from "@/components/ui/button";

/** 만료·사용된 링크. 서버가 만료·사용·위조를 구분하지 않으므로 문구도 하나로 유지 */
export function ExpiredLink({ description }: { description: string }) {
  return (
    <div className="space-y-4 text-center">
      <h1 className="text-xl font-semibold">링크를 사용할 수 없습니다</h1>
      <p className="text-sm text-muted-foreground">{description}</p>
      <Button asChild variant="outline">
        <Link href="/login">로그인 화면으로</Link>
      </Button>
    </div>
  );
}
