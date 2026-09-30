import { Plus } from "lucide-react";
import Link from "next/link";
import { notFound } from "next/navigation";

import { ActionMenu } from "@/components/catalog/action-menu";
import { DeleteDialog } from "@/components/catalog/delete-dialog";
import { EnvironmentFields, ProjectFields, SuiteFields } from "@/components/catalog/entity-fields";
import { FormDialog } from "@/components/catalog/form-dialog";
import {
  EDIT_DELETE,
  ENVIRONMENT_MENU,
  maintenanceMenu,
  WEBHOOK_MENU,
  withHistory,
} from "@/components/catalog/menu-items";
import { Empty, SectionHeader, TableFrame } from "@/components/catalog/section";
import { MaintenanceBadge } from "@/components/maintenance/maintenance-badge";
import { MaintenanceFields } from "@/components/maintenance/maintenance-fields";
import { TestNotificationButton } from "@/components/notification/test-notification-button";
import { WebhookFields } from "@/components/notification/webhook-fields";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  createEnvironment,
  createMaintenanceWindow,
  createSuite,
  deleteEnvironment,
  deleteMaintenanceWindow,
  deleteProject,
  deleteSuite,
  removeProjectWebhook,
  sendTestNotification,
  setProjectWebhook,
  updateEnvironment,
  updateMaintenanceWindow,
  updateProject,
  updateSuite,
} from "@/lib/actions/catalog";
import { apiGet, apiGetOrNotFound } from "@/lib/api/server";
import type { EnvironmentItem, MaintenanceWindow, ProjectItem, SuiteItem } from "@/lib/api/types";
import { historyHref, projectHistoryHref, suiteHistoryHref } from "@/lib/change-log";
import { copyName, MAX_ENVIRONMENT_NAME_LENGTH } from "@/lib/copy-name";
import { formatPeriod, maintenanceOf } from "@/lib/maintenance";

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
  const [environments, suites, allWindows] = await Promise.all([
    apiGet<EnvironmentItem[]>(`/api/environments?projectId=${project.id}`),
    apiGet<SuiteItem[]>(`/api/suites?projectId=${project.id}`),
    apiGet<MaintenanceWindow[]>("/api/maintenance-windows"),
  ]);
  const windows = allWindows.filter((window) => window.projectId === project.id);
  const now = new Date();

  return (
    <div className="space-y-10">
      <PageHeader
        back={{ href: "/projects", label: "프로젝트" }}
        title={project.name}
        description={project.description}
        actions={
          <ActionMenu
            label={`프로젝트 ${project.name} 작업`}
            items={withHistory(EDIT_DELETE, projectHistoryHref(project.id))}
            size="page"
          >
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
                {environments.map((environment) => {
                  const maintenance = maintenanceOf(windows, environment.id);
                  return (
                    <TableRow key={environment.id}>
                      <TableCell className="font-medium">
                        <div className="flex flex-wrap items-center gap-2">
                          {environment.name}
                          {maintenance && <MaintenanceBadge window={maintenance} now={now} />}
                        </div>
                      </TableCell>
                      <TableCell className="font-mono text-xs">{environment.baseUrl}</TableCell>
                      <TableCell className="font-mono text-xs whitespace-normal text-muted-foreground">
                        {Object.keys(environment.variables).join(", ") || "-"}
                      </TableCell>
                      <TableCell className="font-mono text-xs whitespace-normal text-muted-foreground">
                        {environment.secretNames.join(", ") || "-"}
                      </TableCell>
                      <TableCell className="text-right">
                        <ActionMenu
                          label={`환경 ${environment.name} 작업`}
                          items={withHistory(ENVIRONMENT_MENU, historyHref("ENVIRONMENT", environment.id))}
                        >
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
                          <FormDialog
                            menuId="maintenance"
                            title={`${environment.name} 점검 시간 등록`}
                            description="점검 중에는 이 환경의 스케줄 실행을 건너뜁니다 (실행 기록·실패 알림 없음). 수동 실행은 할 수 있습니다."
                            submitLabel="등록"
                            action={createMaintenanceWindow.bind(null, environment.id)}
                          >
                            <MaintenanceFields now={now} />
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
                  );
                })}
              </TableBody>
            </Table>
          </TableFrame>
        )}
      </section>

      {windows.length > 0 && (
        <section className="space-y-3">
          <SectionHeader
            title="점검 시간"
            count={windows.length}
            description="점검 중에는 그 환경의 스케줄 실행을 건너뜀. 끝나면 따로 조치 없이 다시 실행 (KST)"
          />
          <TableFrame>
            <Table aria-label="점검 시간">
              <TableHeader>
                <TableRow>
                  <TableHead>환경</TableHead>
                  <TableHead>기간</TableHead>
                  <TableHead>사유</TableHead>
                  <TableHead>등록</TableHead>
                  <TableHead className="w-12">
                    <span className="sr-only">작업</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {windows.map((window) => (
                  <TableRow key={window.id}>
                    <TableCell className="font-medium">
                      <div className="flex flex-wrap items-center gap-2">
                        {window.environment.name}
                        <MaintenanceBadge window={window} now={now} />
                      </div>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{formatPeriod(window.startsAt, window.endsAt)}</TableCell>
                    <TableCell className="whitespace-normal text-muted-foreground">{window.reason ?? "-"}</TableCell>
                    <TableCell className="text-muted-foreground">{window.createdBy?.name ?? "-"}</TableCell>
                    <TableCell className="text-right">
                      <ActionMenu
                        label={`${window.environment.name} 점검 시간 작업`}
                        items={withHistory(
                          maintenanceMenu(window.active),
                          historyHref("MAINTENANCE_WINDOW", window.id),
                        )}
                      >
                        <FormDialog
                          menuId="edit"
                          title={`${window.environment.name} 점검 시간 수정`}
                          submitLabel="저장"
                          action={updateMaintenanceWindow.bind(null, window.id)}
                        >
                          <MaintenanceFields window={window} now={now} />
                        </FormDialog>
                        <DeleteDialog
                          menuId="delete"
                          title={
                            window.active
                              ? `${window.environment.name} 점검 지금 종료`
                              : `${window.environment.name} 점검 취소`
                          }
                          description={
                            window.active
                              ? "다음 스케줄부터 이 환경의 스케줄을 다시 실행합니다."
                              : "예정된 점검을 취소합니다. 스케줄은 그대로 실행됩니다."
                          }
                          confirmLabel={window.active ? "지금 종료" : "점검 취소"}
                          action={deleteMaintenanceWindow.bind(null, window.id)}
                        />
                      </ActionMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableFrame>
        </section>
      )}

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
                      <ActionMenu
                        label={`스위트 ${suite.name} 작업`}
                        items={withHistory(EDIT_DELETE, suiteHistoryHref(suite.id))}
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

      <section className="space-y-3">
        <SectionHeader
          title="알림 채널"
          description="스케줄 실행 실패·복구 알림을 보낼 곳"
          action={
            project.webhookConfigured ? (
              <ActionMenu label="알림 채널 작업" items={WEBHOOK_MENU}>
                <FormDialog
                  menuId="edit"
                  title="Webhook 변경"
                  submitLabel="저장"
                  action={setProjectWebhook.bind(null, project.id)}
                >
                  <WebhookFields configured />
                </FormDialog>
                <DeleteDialog
                  menuId="delete"
                  title="기본 채널로 되돌리기"
                  description="프로젝트 Webhook을 삭제합니다. 이후 알림은 서버 기본 채널로 보냅니다."
                  confirmLabel="되돌리기"
                  action={removeProjectWebhook.bind(null, project.id)}
                />
              </ActionMenu>
            ) : (
              <FormDialog
                trigger={
                  <Button variant="outline" size="sm">
                    <Plus /> Webhook 지정
                  </Button>
                }
                title="Webhook 지정"
                description="이 프로젝트의 스케줄 실행 실패·복구 알림을 이 채널로 보냅니다."
                submitLabel="저장"
                action={setProjectWebhook.bind(null, project.id)}
              >
                <WebhookFields configured={false} />
              </FormDialog>
            )
          }
        />
        <div className="space-y-3 rounded-xl border p-4 text-sm">
          {project.webhookConfigured ? (
            <>
              <p>이 프로젝트 전용 Webhook으로 보냅니다 (주소는 보안상 표시하지 않음)</p>
              <TestNotificationButton action={sendTestNotification.bind(null, project.id)} />
            </>
          ) : (
            <p className="text-muted-foreground">
              서버 기본 채널로 보냅니다. 이 프로젝트를 담당하는 채널이 따로 있으면 Webhook을 지정해 주세요
            </p>
          )}
        </div>
      </section>
    </div>
  );
}
