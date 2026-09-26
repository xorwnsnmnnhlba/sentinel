import { Play } from "lucide-react";
import { notFound } from "next/navigation";

import { CaseEditor } from "@/components/catalog/case-editor";
import { FormDialog } from "@/components/catalog/form-dialog";
import { RunCaseFields } from "@/components/catalog/run-case-fields";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { runCase, updateCase } from "@/lib/actions/catalog";
import { apiGet, apiGetOrNotFound } from "@/lib/api/server";
import type { CaseItem, EnvironmentItem, SuiteItem } from "@/lib/api/types";
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
  const environments = await apiGet<EnvironmentItem[]>(`/api/environments?projectId=${suite.projectId}`);

  return (
    <div className="max-w-3xl space-y-6">
      <PageHeader
        back={{ href: `/suites/${suite.id}`, label: suite.name }}
        title="케이스 수정"
        description="저장하면 요청 정의와 판정 규칙이 통째로 바뀝니다. 이전 실행 이력은 그대로 유지됩니다."
        actions={
          environments.length > 0 && (
            <FormDialog
              trigger={
                <Button variant="outline">
                  <Play /> 실행
                </Button>
              }
              title={`케이스 "${testCase.name}" 실행`}
              description="저장된 내용으로 이 케이스만 실행합니다. 수정 중인 내용은 먼저 저장해 주세요. 완료 후 결과 화면으로 이동합니다."
              submitLabel="실행"
              action={runCase.bind(null, testCase.id)}
            >
              <RunCaseFields environments={environments} />
            </FormDialog>
          )
        }
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
