import { notFound } from "next/navigation";

import { CaseEditor } from "@/components/catalog/case-editor";
import { PageHeader } from "@/components/page-header";
import { createCase } from "@/lib/actions/catalog";
import { apiGet, apiGetOrNotFound } from "@/lib/api/server";
import type { EnvironmentItem, SuiteItem } from "@/lib/api/types";
import { emptyDraft } from "@/lib/case-draft";

export const metadata = { title: "케이스 추가 · Sentinel" };

/** 케이스 등록. 소속 스위트는 ?suiteId= 로 지정 */
export default async function NewCasePage(props: PageProps<"/cases/new">) {
  const { suiteId } = await props.searchParams;
  if (typeof suiteId !== "string" || !/^\d+$/.test(suiteId)) {
    notFound();
  }
  const suite = await apiGetOrNotFound<SuiteItem>(`/api/suites/${suiteId}`);
  const environments = await apiGet<EnvironmentItem[]>(`/api/environments?projectId=${suite.projectId}`);

  return (
    <div className="max-w-3xl space-y-6">
      <PageHeader back={{ href: `/suites/${suite.id}`, label: suite.name }} title="케이스 추가" />
      <CaseEditor
        initial={emptyDraft()}
        action={createCase.bind(null, suite.id)}
        cancelHref={`/suites/${suite.id}`}
        submitLabel="등록"
        environments={environments}
      />
    </div>
  );
}
