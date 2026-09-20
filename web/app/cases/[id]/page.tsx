import Link from "next/link";
import { notFound } from "next/navigation";

import { CaseEditor } from "@/components/catalog/case-editor";
import { updateCase } from "@/lib/actions/catalog";
import { apiGetOrNotFound } from "@/lib/api/server";
import type { CaseItem, SuiteItem } from "@/lib/api/types";
import { toDraft } from "@/lib/case-draft";

export async function generateMetadata(props: PageProps<"/cases/[id]">) {
  const { id } = await props.params;
  return { title: `케이스 #${id} · Sentinel` };
}

export default async function CasePage(props: PageProps<"/cases/[id]">) {
  const { id } = await props.params;
  if (!/^\d+$/.test(id)) {
    notFound();
  }
  const testCase = await apiGetOrNotFound<CaseItem>(`/api/cases/${id}`);
  const suite = await apiGetOrNotFound<SuiteItem>(`/api/suites/${testCase.suiteId}`);

  return (
    <div className="max-w-3xl space-y-6">
      <div className="space-y-2">
        <Link href={`/suites/${suite.id}`} className="text-sm text-muted-foreground hover:text-foreground">
          ← {suite.name}
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight">케이스 수정</h1>
        <p className="text-sm text-muted-foreground">
          저장하면 요청 정의와 판정 규칙이 통째로 바뀝니다. 이전 실행 이력은 그대로 유지됩니다.
        </p>
      </div>
      <CaseEditor
        initial={toDraft(testCase)}
        action={updateCase.bind(null, testCase.id, suite.id)}
        cancelHref={`/suites/${suite.id}`}
        submitLabel="저장"
      />
    </div>
  );
}
