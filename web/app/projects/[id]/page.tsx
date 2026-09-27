import { Plus } from "lucide-react";
import Link from "next/link";
import { notFound } from "next/navigation";

import { ActionMenu } from "@/components/catalog/action-menu";
import { DeleteDialog } from "@/components/catalog/delete-dialog";
import { EnvironmentFields, ProjectFields, SuiteFields } from "@/components/catalog/entity-fields";
import { FormDialog } from "@/components/catalog/form-dialog";
import { EDIT_DELETE, EDIT_DUPLICATE_DELETE } from "@/components/catalog/menu-items";
import { Empty, SectionHeader, TableFrame } from "@/components/catalog/section";
import { PageHeader } from "@/components/page-header";
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
import { copyName, MAX_ENVIRONMENT_NAME_LENGTH } from "@/lib/copy-name";

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
    <div className="space-y-10">
      <PageHeader
        back={{ href: "/projects", label: "프로젝트" }}
        title={project.name}
        description={project.description}
        actions={
          <ActionMenu label={`프로젝트 ${project.name} 작업`} items={EDIT_DELETE} size="page">
            <FormDialog
              menuId="edit"
              title="프로젝트 수정"
              submitLabel="저장"
              action={updateProject.bind(null, project.id)}
            >
              <ProjectFields project={project} />
            </FormDialog>
            <DeleteDialog
              menuId="delete"
              title={`프로젝트 "${project.name}" 삭제`}
              description="환경·스위트·케이스·스케줄을 함께 삭제합니다. 실행 이력은 유지됩니다."
              action={deleteProject.bind(null, project.id)}
            />
          </ActionMenu>
        }
      />

      <section className="space-y-3">
        <SectionHeader
          title="환경"
          count={environments.length}
          description="GW 단위 실행 대상. 기본 URL과 변수"
          action={
            <FormDialog
              trigger={
                <Button variant="outline" size="sm">
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
          <TableFrame>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>기본 URL</TableHead>
                  <TableHead>변수</TableHead>
                  <TableHead>시크릿</TableHead>
                  <TableHead className="w-12">
                    <span className="sr-only">작업</span>
                  </TableHead>
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
                    <TableCell className="text-right">
                      <ActionMenu label={`환경 ${environment.name} 작업`} items={EDIT_DUPLICATE_DELETE}>
                        <FormDialog
                          menuId="edit"
                          title="환경 수정"
                          submitLabel="저장"
                          action={updateEnvironment.bind(null, environment.id)}
                        >
                          <EnvironmentFields environment={environment} />
                        </FormDialog>
                        <FormDialog
                          menuId="duplicate"
                          title="환경 복제"
                          description={`${environment.name}의 기본 URL·변수를 채운 새 환경을 등록합니다. 원본은 바뀌지 않습니다.`}
                          submitLabel="등록"
                          action={createEnvironment.bind(null, project.id)}
                        >
                          <EnvironmentFields
                            copy={{
                              from: environment,
                              name: copyName(
                                environment.name,
                                environments.map((other) => other.name),
                                MAX_ENVIRONMENT_NAME_LENGTH,
                              ),
                            }}
                          />
                        </FormDialog>
                        <DeleteDialog
                          menuId="delete"
                          title={`환경 "${environment.name}" 삭제`}
                          description="이 환경을 쓰는 스케줄도 함께 삭제합니다. 실행 이력은 유지됩니다."
                          action={deleteEnvironment.bind(null, environment.id)}
                        />
                      </ActionMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableFrame>
        )}
      </section>

      <section className="space-y-3">
        <SectionHeader
          title="스위트"
          count={suites.length}
          description="함께 실행하는 케이스 묶음"
          action={
            <FormDialog
              trigger={
                <Button variant="outline" size="sm">
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
          <TableFrame>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>설명</TableHead>
                  <TableHead className="text-right">케이스</TableHead>
                  <TableHead className="w-12">
                    <span className="sr-only">작업</span>
                  </TableHead>
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
                    <TableCell className="text-right tabular-nums">{suite.caseCount}</TableCell>
                    <TableCell className="text-right">
                      <ActionMenu label={`스위트 ${suite.name} 작업`} items={EDIT_DELETE}>
                        <FormDialog
                          menuId="edit"
                          title="스위트 수정"
                          submitLabel="저장"
                          action={updateSuite.bind(null, suite.id)}
                        >
                          <SuiteFields suite={suite} />
                        </FormDialog>
                        <DeleteDialog
                          menuId="delete"
                          title={`스위트 "${suite.name}" 삭제`}
                          description={`케이스 ${suite.caseCount}건과 스케줄을 함께 삭제합니다. 실행 이력은 유지됩니다.`}
                          action={deleteSuite.bind(null, suite.id, undefined)}
                        />
                      </ActionMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableFrame>
        )}
      </section>
    </div>
  );
}
