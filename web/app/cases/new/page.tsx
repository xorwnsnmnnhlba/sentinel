import Link from "next/link";
import { notFound } from "next/navigation";

import { CaseEditor } from "@/components/catalog/case-editor";
import { createCase } from "@/lib/actions/catalog";
import { apiGetOrNotFound } from "@/lib/api/server";
import type { SuiteItem } from "@/lib/api/types";
import { emptyDraft } from "@/lib/case-draft";

export const metadata = { title: "케이스 추가 · Sentinel" };

/** 케이스 등록. 소속 스위트는 ?suiteId= 로 지정 */
export default async function NewCasePage(props: PageProps<"/cases/new">) {
  const { suiteId } = await props.searchParams;
  if (typeof suiteId !== "string" || !/^\d+$/.test(suiteId)) {
    notFound();
  }
  const suite = await apiGetOrNotFound<SuiteItem>(`/api/suites/${suiteId}`);

  return (
    <div className="max-w-3xl space-y-6">
      <div className="space-y-2">
        <Link href={`/suites/${suite.id}`} className="text-sm text-muted-foreground hover:text-foreground">
          ← {suite.name}
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight">케이스 추가</h1>
      </div>
      <CaseEditor
        initial={emptyDraft()}
        action={createCase.bind(null, suite.id)}
        cancelHref={`/suites/${suite.id}`}
        submitLabel="등록"
      />
    </div>
  );
}
