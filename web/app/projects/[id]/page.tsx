import { Pencil, Plus } from "lucide-react";
import Link from "next/link";
import { notFound } from "next/navigation";

import { DeleteButton } from "@/components/catalog/delete-button";
import { EnvironmentFields, ProjectFields, SuiteFields } from "@/components/catalog/entity-fields";
import { FormDialog } from "@/components/catalog/form-dialog";
import { Empty, RowActions, SectionHeader } from "@/components/catalog/section";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  createEnvironment,
  createSuite,
  deleteEnvironment,
  deleteProject,
  deleteSuite,
  updateEnvironment,
  updateProject,
  updateSuite,
} from "@/lib/actions/catalog";
import { apiGet, apiGetOrNotFound } from "@/lib/api/server";
import type { EnvironmentItem, ProjectItem, SuiteItem } from "@/lib/api/types";

export async function generateMetadata(props: PageProps<"/projects/[id]">) {
  const { id } = await props.params;
  return { title: `프로젝트 #${id} · Sentinel` };
}

export default async function ProjectPage(props: PageProps<"/projects/[id]">) {
  const { id } = await props.params;
  if (!/^\d+$/.test(id)) {
    notFound();
  }
  const project = await apiGetOrNotFound<ProjectItem>(`/api/projects/${id}`);
  const [environments, suites] = await Promise.all([
    apiGet<EnvironmentItem[]>(`/api/environments?projectId=${project.id}`),
    apiGet<SuiteItem[]>(`/api/suites?projectId=${project.id}`),
  ]);

  return (
    <div className="space-y-8">
      <div className="space-y-2">
        <Link href="/projects" className="text-sm text-muted-foreground hover:text-foreground">
          ← 프로젝트
        </Link>
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="space-y-1">
            <h1 className="text-2xl font-semibold tracking-tight">{project.name}</h1>
            {project.description && <p className="text-sm text-muted-foreground">{project.description}</p>}
          </div>
          <div className="flex gap-2">
            <FormDialog
              trigger={
                <Button variant="outline" size="sm">
                  <Pencil /> 수정
                </Button>
              }
              title="프로젝트 수정"
              submitLabel="저장"
              action={updateProject.bind(null, project.id)}
            >
              <ProjectFields project={project} />
            </FormDialog>
            <DeleteButton
              label="삭제"
              title={`프로젝트 "${project.name}" 삭제`}
              description="환경·스위트·케이스·스케줄을 함께 삭제합니다. 실행 이력은 유지됩니다."
              action={deleteProject.bind(null, project.id)}
            />
          </div>
        </div>
      </div>

      <section className="space-y-3">
        <SectionHeader
          title="환경"
          description="GW 단위 실행 대상. 기본 URL과 변수"
          action={
            <FormDialog
              trigger={
                <Button size="sm">
                  <Plus /> 환경 추가
                </Button>
              }
              title="환경 추가"
              submitLabel="등록"
              action={createEnvironment.bind(null, project.id)}
            >
              <EnvironmentFields />
            </FormDialog>
          }
        />
        {environments.length === 0 ? (
          <Empty message="등록된 환경이 없습니다" />
        ) : (
          <div className="rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>기본 URL</TableHead>
                  <TableHead>변수</TableHead>
                  <TableHead>시크릿</TableHead>
                  <TableHead className="w-24" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {environments.map((environment) => (
                  <TableRow key={environment.id}>
                    <TableCell className="font-medium">{environment.name}</TableCell>
                    <TableCell className="font-mono text-xs">{environment.baseUrl}</TableCell>
                    <TableCell className="font-mono text-xs whitespace-normal text-muted-foreground">
                      {Object.keys(environment.variables).join(", ") || "-"}
                    </TableCell>
                    <TableCell className="font-mono text-xs whitespace-normal text-muted-foreground">
                      {environment.secretNames.join(", ") || "-"}
                    </TableCell>
                    <TableCell>
                      <RowActions>
                        <FormDialog
                          trigger={
                            <Button variant="ghost" size="icon-sm" aria-label={`환경 ${environment.name} 수정`}>
                              <Pencil />
                            </Button>
                          }
                          title="환경 수정"
                          submitLabel="저장"
                          action={updateEnvironment.bind(null, environment.id)}
                        >
                          <EnvironmentFields environment={environment} />
                        </FormDialog>
                        <DeleteButton
                          title={`환경 "${environment.name}" 삭제`}
                          description="이 환경을 쓰는 스케줄도 함께 삭제합니다. 실행 이력은 유지됩니다."
                          action={deleteEnvironment.bind(null, environment.id)}
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

      <section className="space-y-3">
        <SectionHeader
          title="스위트"
          description="함께 실행하는 케이스 묶음"
          action={
            <FormDialog
              trigger={
                <Button size="sm">
                  <Plus /> 스위트 추가
                </Button>
              }
              title="스위트 추가"
              submitLabel="등록"
              action={createSuite.bind(null, project.id)}
            >
              <SuiteFields />
            </FormDialog>
          }
        />
        {suites.length === 0 ? (
          <Empty message="등록된 스위트가 없습니다" />
        ) : (
          <div className="rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>설명</TableHead>
                  <TableHead className="text-right">케이스</TableHead>
                  <TableHead className="w-24" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {suites.map((suite) => (
                  <TableRow key={suite.id}>
                    <TableCell className="font-medium">
                      <Link href={`/suites/${suite.id}`} className="hover:underline">
                        {suite.name}
                      </Link>
                    </TableCell>
                    <TableCell className="whitespace-normal text-muted-foreground">
                      {suite.description ?? "-"}
                    </TableCell>
                    <TableCell className="text-right">{suite.caseCount}</TableCell>
                    <TableCell>
                      <RowActions>
                        <FormDialog
                          trigger={
                            <Button variant="ghost" size="icon-sm" aria-label={`스위트 ${suite.name} 수정`}>
                              <Pencil />
                            </Button>
                          }
                          title="스위트 수정"
                          submitLabel="저장"
                          action={updateSuite.bind(null, suite.id)}
                        >
                          <SuiteFields suite={suite} />
                        </FormDialog>
                        <DeleteButton
                          title={`스위트 "${suite.name}" 삭제`}
                          description={`케이스 ${suite.caseCount}건과 스케줄을 함께 삭제합니다. 실행 이력은 유지됩니다.`}
                          action={deleteSuite.bind(null, suite.id, undefined)}
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
