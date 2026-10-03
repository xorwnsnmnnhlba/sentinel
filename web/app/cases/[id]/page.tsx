import { FileClock } from "lucide-react";
import Link from "next/link";
import { notFound } from "next/navigation";

import { CaseEditor } from "@/components/catalog/case-editor";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { updateCase } from "@/lib/actions/catalog";
import { apiGet, apiGetOrNotFound } from "@/lib/api/server";
import type { CaseItem, EnvironmentItem, SuiteItem } from "@/lib/api/types";
import { toDraft } from "@/lib/case-draft";
import { historyHref } from "@/lib/change-log";

export async function generateMetadata(props: PageProps<"/cases/[id]">) {
  const { id } = await props.params;
  return { title: `케이스 #${id} · Sentinel` };
}

export default async function CasePage(props: PageProps<"/cases/[id]">) {
  const { id } = await props.params;
  if (!/^\d+$/.test(id)) {
    notFound();
  }
  const { notice } = await props.searchParams;
  const testCase = await apiGetOrNotFound<CaseItem>(`/api/cases/${id}`);
  const suite = await apiGetOrNotFound<SuiteItem>(`/api/suites/${testCase.suiteId}`);
  const [environments, siblings] = await Promise.all([
    apiGet<EnvironmentItem[]>(`/api/environments?projectId=${suite.projectId}`),
    apiGet<CaseItem[]>(`/api/cases?suiteId=${suite.id}`),
  ]);

  return (
    <div className="max-w-3xl space-y-6">
      <PageHeader
        back={{ href: `/suites/${suite.id}`, label: suite.name }}
        title="케이스 수정"
        actions={
          <Button asChild variant="outline" size="sm">
            <Link href={historyHref("CASE", testCase.id)}>
              <FileClock /> 변경 이력
            </Link>
          </Button>
        }
      />
      {/* 등록 후 실행에서 등록만 되고 실행이 실패한 경우 (같은 케이스를 다시 등록하지 않도록 수정 화면으로 이동) */}
      {notice === "run-failed" && (
        <p role="status" className="rounded-md bg-amber-500/10 px-4 py-3 text-sm text-amber-800 dark:text-amber-300">
          케이스를 등록했지만 실행하지 못했습니다. 환경을 확인한 뒤 &quot;저장 후 실행&quot;으로 다시 실행해 주세요
        </p>
      )}
      <CaseEditor
        initial={toDraft(testCase)}
        action={updateCase.bind(null, testCase.id, suite.id)}
        cancelHref={`/suites/${suite.id}`}
        submitLabel="저장"
        environments={environments}
        // 첫 케이스는 여기까지 실행이 이 케이스만 실행과 같음
        runThrough={siblings.length > 0 && siblings[0].id !== testCase.id}
      />
    </div>
  );
}
