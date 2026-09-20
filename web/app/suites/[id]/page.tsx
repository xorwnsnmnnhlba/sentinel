import { History, Pencil, Plus } from "lucide-react";
import Link from "next/link";
import { notFound } from "next/navigation";

import { DeleteButton } from "@/components/catalog/delete-button";
import { SuiteFields } from "@/components/catalog/entity-fields";
import { FormDialog } from "@/components/catalog/form-dialog";
import { RunSuiteForm } from "@/components/catalog/run-suite-form";
import { Empty, RowActions, SectionHeader } from "@/components/catalog/section";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { deleteCase, deleteSuite, runSuite, updateSuite } from "@/lib/actions/catalog";
import { apiGet, apiGetOrNotFound } from "@/lib/api/server";
import type { CaseItem, EnvironmentItem, ProjectItem, SuiteItem } from "@/lib/api/types";

export async function generateMetadata(props: PageProps<"/suites/[id]">) {
  const { id } = await props.params;
  return { title: `스위트 #${id} · Sentinel` };
}

export default async function SuitePage(props: PageProps<"/suites/[id]">) {
  const { id } = await props.params;
  if (!/^\d+$/.test(id)) {
    notFound();
  }
  const suite = await apiGetOrNotFound<SuiteItem>(`/api/suites/${id}`);
  const [project, environments, cases] = await Promise.all([
    apiGetOrNotFound<ProjectItem>(`/api/projects/${suite.projectId}`),
    apiGet<EnvironmentItem[]>(`/api/environments?projectId=${suite.projectId}`),
    apiGet<CaseItem[]>(`/api/cases?suiteId=${suite.id}`),
  ]);

  return (
    <div className="space-y-8">
      <div className="space-y-2">
        <Link href={`/projects/${project.id}`} className="text-sm text-muted-foreground hover:text-foreground">
          ← {project.name}
        </Link>
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="space-y-1">
            <h1 className="text-2xl font-semibold tracking-tight">{suite.name}</h1>
            {suite.description && <p className="text-sm text-muted-foreground">{suite.description}</p>}
          </div>
          <div className="flex gap-2">
            <Button asChild variant="outline" size="sm">
              <Link href={`/runs?suiteId=${suite.id}`}>
                <History /> 실행 이력
              </Link>
            </Button>
            <FormDialog
              trigger={
                <Button variant="outline" size="sm">
                  <Pencil /> 수정
                </Button>
              }
              title="스위트 수정"
              submitLabel="저장"
              action={updateSuite.bind(null, suite.id)}
            >
              <SuiteFields suite={suite} />
            </FormDialog>
            <DeleteButton
              label="삭제"
              title={`스위트 "${suite.name}" 삭제`}
              description={`케이스 ${cases.length}건과 스케줄을 함께 삭제합니다. 실행 이력은 유지됩니다.`}
              action={deleteSuite.bind(null, suite.id, project.id)}
            />
          </div>
        </div>
      </div>

      <section className="space-y-3">
        <div>
          <h2 className="text-lg font-semibold">실행</h2>
          <p className="text-sm text-muted-foreground">케이스를 ID 순서로 차례대로 실행. 완료 후 결과 화면으로 이동</p>
        </div>
        <RunSuiteForm environments={environments} caseCount={cases.length} action={runSuite.bind(null, suite.id)} />
      </section>

      <section className="space-y-3">
        <SectionHeader
          title="케이스"
          description="요청 정의와 판정 규칙. 실행 순서는 등록 순"
          action={
            <Button asChild size="sm">
              <Link href={`/cases/new?suiteId=${suite.id}`}>
                <Plus /> 케이스 추가
              </Link>
            </Button>
          }
        />
        {cases.length === 0 ? (
          <Empty message="등록된 케이스가 없습니다" />
        ) : (
          <div className="rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-16">#</TableHead>
                  <TableHead>이름</TableHead>
                  <TableHead>요청</TableHead>
                  <TableHead>판정 규칙</TableHead>
                  <TableHead className="w-24" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {cases.map((testCase) => (
                  <TableRow key={testCase.id}>
                    <TableCell className="text-muted-foreground">{testCase.id}</TableCell>
                    <TableCell className="font-medium whitespace-normal">
                      <Link href={`/cases/${testCase.id}`} className="hover:underline">
                        {testCase.name}
                      </Link>
                    </TableCell>
                    <TableCell className="min-w-72 whitespace-normal">
                      <div className="flex items-start gap-2 font-mono text-xs">
                        <Badge variant="outline" className="shrink-0 font-mono">
                          {testCase.request.method}
                        </Badge>
                        <span className="[overflow-wrap:anywhere]">{testCase.request.path}</span>
                      </div>
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground">
                      {testCase.assertions.length > 0
                        ? testCase.assertions.map((assertion) => assertion.type).join(", ")
                        : "기본 (2xx)"}
                    </TableCell>
                    <TableCell>
                      <RowActions>
                        <Button asChild variant="ghost" size="icon-sm">
                          <Link href={`/cases/${testCase.id}`} aria-label={`케이스 ${testCase.name} 수정`}>
                            <Pencil />
                          </Link>
                        </Button>
                        <DeleteButton
                          title={`케이스 "${testCase.name}" 삭제`}
                          description="이 케이스의 실행 이력은 유지됩니다."
                          action={deleteCase.bind(null, testCase.id)}
                        />
                      </RowActions>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </section>
    </div>
  );
}
