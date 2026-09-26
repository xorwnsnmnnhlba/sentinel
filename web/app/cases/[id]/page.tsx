import { PageHeader } from "@/components/page-header";
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
      <PageHeader
        back={{ href: `/suites/${suite.id}`, label: suite.name }}
        title="케이스 수정"
        description="저장하면 요청 정의와 판정 규칙이 통째로 바뀝니다. 이전 실행 이력은 그대로 유지됩니다."
      />
      <CaseEditor
        initial={toDraft(testCase)}
        action={updateCase.bind(null, testCase.id, suite.id)}
        cancelHref={`/suites/${suite.id}`}
        submitLabel="저장"
      />
    </div>
  );
}
