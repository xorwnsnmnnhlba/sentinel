import { ArrowUpDown, History, Plus } from "lucide-react";
import Link from "next/link";
import { notFound } from "next/navigation";

import { ActionMenu } from "@/components/catalog/action-menu";
import { CaseImportDialog } from "@/components/catalog/case-import-dialog";
import { CaseOrderFields } from "@/components/catalog/case-order-fields";
import { DeleteDialog } from "@/components/catalog/delete-dialog";
import { SuiteFields } from "@/components/catalog/entity-fields";
import { FormDialog } from "@/components/catalog/form-dialog";
import { MethodBadge } from "@/components/catalog/method-badge";
import { caseMenu, EDIT_DELETE, withHistory } from "@/components/catalog/menu-items";
import { RunCaseFields } from "@/components/catalog/run-case-fields";
import { RunSuiteForm } from "@/components/catalog/run-suite-form";
import { ScheduleFields } from "@/components/catalog/schedule-fields";
import { ScheduleToggle } from "@/components/catalog/schedule-toggle";
import { Empty, SectionHeader, TableFrame } from "@/components/catalog/section";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  createSchedule,
  deleteCase,
  deleteSchedule,
  deleteSuite,
  duplicateCase,
  reorderCases,
  runCase,
  runSuite,
  runThroughCase,
  updateSchedule,
  updateSuite,
} from "@/lib/actions/catalog";
import { apiGet, apiGetOrNotFound } from "@/lib/api/server";
import type { CaseItem, EnvironmentItem, ProjectItem, ScheduleDetail, SuiteItem } from "@/lib/api/types";
import { historyHref, suiteHistoryHref } from "@/lib/change-log";
import { formatDateTime } from "@/lib/format";

import { ResponseTimes } from "./response-times";

export async function generateMetadata(props: PageProps<"/suites/[id]">) {
  const { id } = await props.params;
  return { title: `스위트 #${id} · Sentinel` };
}

export default async function SuitePage(props: PageProps<"/suites/[id]">) {
  const { id } = await props.params;
  if (!/^\d+$/.test(id)) {
    notFound();
  }
  const searchParams = await props.searchParams;
  const suite = await apiGetOrNotFound<SuiteItem>(`/api/suites/${id}`);
  const [project, environments, cases, schedules] = await Promise.all([
    apiGetOrNotFound<ProjectItem>(`/api/projects/${suite.projectId}`),
    apiGet<EnvironmentItem[]>(`/api/environments?projectId=${suite.projectId}`),
    apiGet<CaseItem[]>(`/api/cases?suiteId=${suite.id}`),
    apiGet<ScheduleDetail[]>(`/api/schedules?suiteId=${suite.id}`),
  ]);
  const environmentNames = new Map(environments.map((environment) => [environment.id, environment.name]));

  return (
    <div className="space-y-10">
      <PageHeader
        back={{ href: `/projects/${project.id}`, label: project.name }}
        title={suite.name}
        description={
          <>
            {suite.description && <p>{suite.description}</p>}
            <p className="text-xs">
              케이스 {cases.length} · 스케줄 {schedules.length} · 케이스를 목록 순서대로 실행, 완료 후 결과 화면으로
              이동
            </p>
          </>
        }
        actions={
          <>
            <Button asChild variant="outline">
              <Link href={`/runs?suiteId=${suite.id}`}>
                <History /> 실행 이력
              </Link>
            </Button>
            <RunSuiteForm environments={environments} caseCount={cases.length} action={runSuite.bind(null, suite.id)} />
            <ActionMenu
              label={`스위트 ${suite.name} 작업`}
              items={withHistory(EDIT_DELETE, suiteHistoryHref(suite.id))}
              size="page"
            >
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
                description={`케이스 ${cases.length}건과 스케줄을 함께 삭제합니다. 실행 이력은 유지됩니다.`}
                action={deleteSuite.bind(null, suite.id, project.id)}
              />
            </ActionMenu>
          </>
        }
      />

      <section className="space-y-3">
        <SectionHeader
          title="케이스"
          count={cases.length}
          description="요청 정의와 판정 규칙. 목록 순서대로 실행 (새 케이스는 마지막)"
          action={
            <>
              {cases.length > 1 && (
                <FormDialog
                  trigger={
                    <Button variant="outline" size="sm">
                      <ArrowUpDown /> 순서 변경
                    </Button>
                  }
                  title="케이스 실행 순서"
                  description="스위트 실행과 목록에 이 순서가 적용됩니다. 값 추출은 앞 케이스에서 뒤 케이스로 전달됩니다."
                  submitLabel="저장"
                  action={reorderCases.bind(null, suite.id)}
                >
                  <CaseOrderFields
                    cases={cases.map((testCase) => ({
                      id: testCase.id,
                      name: testCase.name,
                      method: testCase.request.method,
                      path: testCase.request.path,
                    }))}
                  />
                </FormDialog>
              )}
              <CaseImportDialog suiteId={suite.id} existingNames={cases.map((testCase) => testCase.name)} />
              <Button asChild variant="outline" size="sm">
                <Link href={`/cases/new?suiteId=${suite.id}`}>
                  <Plus /> 케이스 추가
                </Link>
              </Button>
            </>
          }
        />
        {cases.length === 0 ? (
          <Empty message="등록된 케이스가 없습니다" />
        ) : (
          <TableFrame>
            <Table aria-label="케이스 목록">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-14">순서</TableHead>
                  <TableHead>이름</TableHead>
                  <TableHead>요청</TableHead>
                  <TableHead>판정 규칙</TableHead>
                  <TableHead className="w-12">
                    <span className="sr-only">작업</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {cases.map((testCase, index) => (
                  <TableRow key={testCase.id}>
                    <TableCell className="text-muted-foreground tabular-nums">{index + 1}</TableCell>
                    <TableCell className="font-medium whitespace-normal">
                      <Link href={`/cases/${testCase.id}`} className="hover:underline">
                        {testCase.name}
                      </Link>
                    </TableCell>
                    <TableCell className="min-w-72 whitespace-normal">
                      <div className="flex items-start gap-2 font-mono text-xs">
                        <MethodBadge method={testCase.request.method} />
                        <span className="[overflow-wrap:anywhere]">{testCase.request.path}</span>
                      </div>
                      {testCase.extractors.length > 0 && (
                        // 뒤 케이스에 넘기는 변수. 스위트 실행 순서(목록 순)대로 값이 전달됨
                        <p className="mt-1 text-xs [overflow-wrap:anywhere] text-muted-foreground">
                          추출{" "}
                          <span className="font-mono">
                            {testCase.extractors.map((extractor) => `{{${extractor.name}}}`).join(" ")}
                          </span>
                        </p>
                      )}
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground">
                      {testCase.assertions.length > 0
                        ? testCase.assertions.map((assertion) => assertion.type).join(", ")
                        : "기본 (2xx)"}
                    </TableCell>
                    <TableCell className="text-right">
                      <ActionMenu
                        label={`케이스 ${testCase.name} 작업`}
                        items={withHistory(
                          caseMenu(testCase.id, {
                            runnable: environments.length > 0,
                            first: index === 0,
                            duplicate: duplicateCase.bind(null, testCase.id),
                          }),
                          historyHref("CASE", testCase.id),
                        )}
                      >
                        {environments.length > 0 && (
                          <FormDialog
                            menuId="run"
                            title={`케이스 "${testCase.name}" 실행`}
                            description="이 케이스만 실행합니다. 완료 후 결과 화면으로 이동합니다."
                            submitLabel="실행"
                            action={runCase.bind(null, testCase.id)}
                          >
                            <RunCaseFields environments={environments} />
                          </FormDialog>
                        )}
                        {environments.length > 0 && index > 0 && (
                          <FormDialog
                            menuId="run-through"
                            title={`케이스 "${testCase.name}"까지 실행`}
                            description={`스위트의 처음부터 이 케이스까지 ${index + 1}건을 실행 순서대로 실행합니다. 앞 케이스가 추출한 값을 이 케이스에서 쓸 수 있습니다. 완료 후 결과 화면으로 이동합니다.`}
                            submitLabel="실행"
                            action={runThroughCase.bind(null, testCase.id)}
                          >
                            <RunCaseFields environments={environments} />
                          </FormDialog>
                        )}
                        <DeleteDialog
                          menuId="delete"
                          title={`케이스 "${testCase.name}" 삭제`}
                          description="이 케이스의 실행 이력은 유지됩니다."
                          action={deleteCase.bind(null, testCase.id)}
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
          title="스케줄"
          count={schedules.length}
          description={`정기 실행. cron은 ${schedules[0]?.zone ?? "Asia/Seoul"} 기준, 놓친 실행은 1회만 실행`}
          action={
            environments.length > 0 && (
              <FormDialog
                trigger={
                  <Button variant="outline" size="sm">
                    <Plus /> 스케줄 추가
                  </Button>
                }
                title="스케줄 추가"
                submitLabel="등록"
                action={createSchedule.bind(null, suite.id)}
              >
                <ScheduleFields environments={environments} />
              </FormDialog>
            )
          }
        />
        {schedules.length === 0 ? (
          <Empty message={environments.length === 0 ? "환경을 먼저 등록해 주세요" : "등록된 스케줄이 없습니다"} />
        ) : (
          <TableFrame>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>cron</TableHead>
                  <TableHead>환경</TableHead>
                  <TableHead>다음 실행 (KST)</TableHead>
                  <TableHead>마지막 실행 (KST)</TableHead>
                  <TableHead className="w-32">상태</TableHead>
                  <TableHead className="w-12">
                    <span className="sr-only">작업</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {schedules.map((schedule) => (
                  <TableRow key={schedule.id}>
                    <TableCell className="font-mono text-xs">{schedule.cron}</TableCell>
                    <TableCell>{environmentNames.get(schedule.environmentId) ?? "-"}</TableCell>
                    <TableCell className="font-mono text-xs">
                      {schedule.nextRunAt ? formatDateTime(schedule.nextRunAt) : "-"}
                    </TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">
                      {schedule.lastTriggeredAt ? formatDateTime(schedule.lastTriggeredAt) : "-"}
                    </TableCell>
                    <TableCell>
                      <ScheduleToggle
                        scheduleId={schedule.id}
                        enabled={schedule.enabled}
                        label={`스케줄 ${schedule.cron} 활성`}
                      />
                    </TableCell>
                    <TableCell className="text-right">
                      <ActionMenu
                        label={`스케줄 ${schedule.cron} 작업`}
                        items={withHistory(EDIT_DELETE, historyHref("SCHEDULE", schedule.id))}
                      >
                        <FormDialog
                          menuId="edit"
                          title="스케줄 수정"
                          description="환경·cron 변경. 활성 상태면 다음 실행 시각을 현재 시각 기준으로 다시 계산합니다."
                          submitLabel="저장"
                          action={updateSchedule.bind(null, schedule.id)}
                        >
                          <ScheduleFields environments={environments} schedule={schedule} />
                        </FormDialog>
                        <DeleteDialog
                          menuId="delete"
                          title={`스케줄 "${schedule.cron}" 삭제`}
                          description="정기 실행이 중지됩니다. 실행 이력은 유지됩니다."
                          action={deleteSchedule.bind(null, schedule.id)}
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

      <ResponseTimes
        suiteId={suite.id}
        environments={environments}
        hasCases={cases.length > 0}
        searchParams={searchParams}
      />
    </div>
  );
}
