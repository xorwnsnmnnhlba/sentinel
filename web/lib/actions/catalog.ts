"use server";

import { refresh } from "next/cache";
import { redirect } from "next/navigation";

import { apiGet, apiSend, isApiError } from "@/lib/api/server";
import type { CaseItem, EnvironmentItem, MaintenanceWindow, ProjectItem, RunDetail, SuiteItem } from "@/lib/api/types";
import { copyName, MAX_CASE_NAME_LENGTH } from "@/lib/copy-name";
import { type CaseDraft, isCaseDraft, validateDraft } from "@/lib/case-draft";
import { describeInputError } from "@/lib/input-error";
import { fromKstInput, periodError } from "@/lib/maintenance";
import { MAX_BULK_UPDATE } from "@/lib/schedule-overview";
import { type Delivery, webhookUrlError } from "@/lib/webhook";

/**
 * 프로젝트·환경·스위트·케이스 등록·수정·삭제, 스위트 실행. Spring 호출 후 refresh()로 현재 화면 재조회 또는 redirect.
 *
 * Server Action은 화면을 거치지 않고 직접 POST로도 호출 가능하므로 입력값(바인딩된 ID 포함)을 모두 신뢰하지 않고 검사.
 * 값 검증의 최종 판단은 Spring(400·404·409), 여기서는 형식만 확인
 */

/** 폼 결과. 오류 시 화면에 표시할 메시지 */
export type ActionState = { error?: string } | undefined;

export async function createProject(_: ActionState, form: FormData): Promise<ActionState> {
  let created: ProjectItem | null;
  try {
    created = await apiSend<ProjectItem>("POST", "/api/projects", {
      name: text(form, "name"),
      description: text(form, "description"),
    });
  } catch (error) {
    return failure(error);
  }
  redirect(`/projects/${created!.id}`);
}

export async function updateProject(projectId: number, _: ActionState, form: FormData): Promise<ActionState> {
  return mutate("PATCH", `/api/projects/${id(projectId)}`, {
    name: raw(form, "name"),
    description: raw(form, "description"),
  });
}

/** 실패 알림을 보낼 프로젝트 Webhook 지정·변경. 주소는 서버에서 암호화하여 저장 */
export async function setProjectWebhook(projectId: number, _: ActionState, form: FormData): Promise<ActionState> {
  const url = String(form.get("webhookUrl") ?? "").trim();
  const error = webhookUrlError(url);
  if (error) {
    return { error };
  }
  return mutate("PATCH", `/api/projects/${id(projectId)}`, { webhookUrl: url });
}

/** 프로젝트 Webhook 삭제. 이후 알림은 서버 기본 채널로 */
export async function removeProjectWebhook(projectId: number): Promise<ActionState> {
  return mutate("PATCH", `/api/projects/${id(projectId)}`, { webhookUrl: "" });
}

export type TestNotificationState = { delivery?: Delivery; error?: string } | undefined;

/** 프로젝트 Webhook으로 연결 확인 알림 발송. 결과(받았는지·상태 코드·사유)를 그대로 반환 */
export async function sendTestNotification(projectId: number): Promise<TestNotificationState> {
  let sent: Delivery | null;
  try {
    sent = await apiSend<Delivery>("POST", "/api/notification-deliveries", { projectId: id(projectId) });
  } catch (error) {
    if (isApiError(error) && error.status === 409) {
      refresh();
      return { error: "지정된 Webhook이 없습니다" };
    }
    if (isApiError(error) && error.status === 400) {
      return { error: "Webhook 주소를 읽을 수 없습니다. 서버 암호화 키가 바뀌었다면 주소를 다시 지정해 주세요" };
    }
    return failure(error);
  }
  return { delivery: deliveryOf(sent) };
}

/**
 * 서버 기본 채널로 연결 확인 알림 발송 (관리자). 결과를 그대로 반환하고 알림 이력을 다시 받음
 */
export async function sendDefaultTestNotification(): Promise<TestNotificationState> {
  let sent: Delivery | null;
  try {
    sent = await apiSend<Delivery>("POST", "/api/notification-deliveries", {});
  } catch (error) {
    if (isApiError(error) && error.status === 409) {
      refresh();
      return { error: "기본 알림 채널 주소(SENTINEL_WEBHOOK_URL)가 설정되지 않아 보낼 수 없습니다" };
    }
    if (isApiError(error) && error.status === 403) {
      return { error: "기본 채널 테스트 알림은 관리자만 보낼 수 있습니다" };
    }
    return failure(error);
  }
  refresh();
  return { delivery: deliveryOf(sent) };
}

/** 만든 발송 기록에서 결과만 (응답은 발송 이력 항목 전체) */
function deliveryOf(sent: Delivery | null): Delivery | undefined {
  return sent ? { delivered: sent.delivered, statusCode: sent.statusCode, error: sent.error } : undefined;
}

export async function deleteProject(projectId: number): Promise<ActionState> {
  try {
    await apiSend("DELETE", `/api/projects/${id(projectId)}`);
  } catch (error) {
    return failure(error);
  }
  redirect("/projects");
}

export async function createEnvironment(projectId: number, _: ActionState, form: FormData): Promise<ActionState> {
  const variables = variablesOf(form);
  if (typeof variables === "string") {
    return { error: variables };
  }
  const secrets = secretsOf(form);
  if (typeof secrets === "string") {
    return { error: secrets };
  }
  return mutate("POST", "/api/environments", {
    projectId: id(projectId),
    name: text(form, "name"),
    baseUrl: text(form, "baseUrl"),
    variables,
    secrets,
  });
}

export async function updateEnvironment(environmentId: number, _: ActionState, form: FormData): Promise<ActionState> {
  const variables = variablesOf(form);
  if (typeof variables === "string") {
    return { error: variables };
  }
  const secrets = secretsOf(form);
  if (typeof secrets === "string") {
    return { error: secrets };
  }
  return mutate("PATCH", `/api/environments/${id(environmentId)}`, {
    name: raw(form, "name"),
    baseUrl: raw(form, "baseUrl"),
    variables,
    secrets,
    version: versionOf(form),
  });
}

export async function deleteEnvironment(environmentId: number): Promise<ActionState> {
  return mutate("DELETE", `/api/environments/${id(environmentId)}`);
}

export async function createSuite(projectId: number, _: ActionState, form: FormData): Promise<ActionState> {
  return mutate("POST", "/api/suites", {
    projectId: id(projectId),
    name: text(form, "name"),
    description: text(form, "description"),
  });
}

export async function updateSuite(suiteId: number, _: ActionState, form: FormData): Promise<ActionState> {
  return mutate("PATCH", `/api/suites/${id(suiteId)}`, {
    name: raw(form, "name"),
    description: raw(form, "description"),
  });
}

/**
 * 케이스 실행 순서 변경. 폼의 caseId를 적힌 순서대로 스위트의 실행 순서로 저장 (Spring이 스위트의 현재 케이스 전체인지 확인)
 */
export async function reorderCases(suiteId: number, _: ActionState, form: FormData): Promise<ActionState> {
  const caseOrder = form.getAll("caseId").map(Number);
  if (caseOrder.length === 0 || !caseOrder.every((caseId) => Number.isSafeInteger(caseId) && caseId > 0)) {
    return { error: "요청 형식이 올바르지 않습니다" };
  }
  try {
    await apiSend<SuiteItem>("PATCH", `/api/suites/${id(suiteId)}`, { caseOrder });
  } catch (error) {
    // 이름 중복이 아닌, 창을 연 뒤 다른 곳에서 케이스가 추가·삭제된 경우. 다시 열면 최신 목록이 보이도록 화면 재조회
    if (isApiError(error) && error.status === 409) {
      refresh();
      return { error: "그사이 케이스가 추가되거나 삭제되었습니다. 창을 닫고 다시 열어 순서를 정해 주세요" };
    }
    return failure(error);
  }
  refresh();
  return {};
}

/**
 * @param projectId 지정 시 삭제 후 프로젝트 화면으로 이동(스위트 화면에서 삭제). 미지정 시 현재 화면 재조회
 */
export async function deleteSuite(suiteId: number, projectId?: number): Promise<ActionState> {
  const result = await mutate("DELETE", `/api/suites/${id(suiteId)}`);
  if (result?.error || projectId === undefined) {
    return result;
  }
  redirect(`/projects/${id(projectId)}`);
}

export async function deleteCase(caseId: number): Promise<ActionState> {
  return mutate("DELETE", `/api/cases/${id(caseId)}`);
}

/** 케이스 저장 결과. 오류 시 화면에 목록으로 표시 */
/** @property version 저장했지만 화면에 남을 때(실행 실패) 저장된 케이스의 version. 이어서 저장할 때 사용 */
export type CaseActionState = { errors?: string[]; version?: number } | undefined;

/**
 * 케이스 등록. 성공 시 스위트 화면으로 이동
 */
/**
 * @param runIn 지정 시 등록 후 그 환경에서 이 케이스만 실행하고 결과 화면으로 이동
 */
export async function createCase(suiteId: number, draft: CaseDraft, runIn?: number): Promise<CaseActionState> {
  return saveCase("POST", "/api/cases", id(suiteId), draft, { suiteId: id(suiteId) }, runIn);
}

/**
 * 케이스 수정. 요청 정의·판정 규칙은 전체 교체, 성공 시 스위트 화면으로 이동
 * @param runIn 지정 시 저장 후 그 환경에서 이 케이스만 실행하고 결과 화면으로 이동
 * @param through runIn과 함께 true면 스위트 처음부터 이 케이스까지 실행
 */
export async function updateCase(
  caseId: number,
  suiteId: number,
  draft: CaseDraft,
  runIn?: number,
  through?: boolean,
): Promise<CaseActionState> {
  return saveCase("PATCH", `/api/cases/${id(caseId)}`, id(suiteId), draft, {}, runIn, through === true);
}

async function saveCase(
  method: "POST" | "PATCH",
  path: string,
  suiteId: number,
  draft: unknown,
  extra: Record<string, number>,
  runIn: unknown,
  through = false,
): Promise<CaseActionState> {
  if (!isCaseDraft(draft)) {
    return { errors: ["요청 형식이 올바르지 않습니다"] };
  }
  // 브라우저에서 임의 값을 보낼 수 있으므로 저장 전에 확인
  if (runIn !== undefined && !(typeof runIn === "number" && Number.isSafeInteger(runIn) && runIn > 0)) {
    return { errors: ["실행할 환경을 선택해 주세요"] };
  }
  const validation = validateDraft(draft);
  if (!validation.ok) {
    return { errors: validation.errors };
  }
  let saved: CaseItem | null;
  try {
    // 수정은 화면을 연 때의 version을 함께 보냄 (그사이 다른 곳에서 고쳤으면 409)
    const version = method === "PATCH" ? { version: draft.version } : {};
    saved = await apiSend<CaseItem>(method, path, { ...extra, ...validation.payload, ...version });
  } catch (error) {
    const message = failure(error)?.error;
    return { errors: message ? [message] : [] };
  }
  if (runIn === undefined) {
    redirect(`/suites/${suiteId}`);
  }

  let run: RunDetail | null;
  try {
    const target = through ? { throughCaseId: saved!.id } : { caseId: saved!.id };
    run = await apiSend<RunDetail>("POST", "/api/runs", { ...target, environmentId: runIn });
  } catch (error) {
    const reason = failure(error)?.error ?? "";
    if (method === "POST") {
      // 등록은 끝났으므로 다시 누르면 같은 케이스가 또 등록됨. 수정 화면으로 옮겨 안내
      redirect(`/cases/${saved!.id}?notice=run-failed`);
    }
    return { errors: [`저장했지만 실행하지 못했습니다. ${reason}`.trim()], version: saved!.version };
  }
  redirect(`/runs/${run!.id}`);
}

/**
 * 케이스 복제. 요청 정의·판정 규칙·값 추출을 그대로 복사해 같은 스위트의 원본 바로 뒤 순서로 등록하고 새 케이스의 수정 화면으로 이동
 * 이름은 "원본 (복사)"부터 스위트 안에서 비어 있는 이름 (Spring API 변경 없이 조회·등록 API 조합)
 */
export async function duplicateCase(caseId: number): Promise<ActionState> {
  let original: CaseItem;
  let siblings: CaseItem[];
  try {
    original = await apiGet<CaseItem>(`/api/cases/${id(caseId)}`);
    siblings = await apiGet<CaseItem[]>(`/api/cases?suiteId=${original.suite.id}`);
  } catch (error) {
    // 세션 만료 시 로그인 화면 이동(redirect)은 그대로 전달
    if (!isApiError(error)) {
      throw error;
    }
    return failure(error);
  }
  let created: CaseItem | null;
  try {
    created = await apiSend<CaseItem>("POST", "/api/cases", {
      suiteId: original.suite.id,
      name: copyName(
        original.name,
        siblings.map((sibling) => sibling.name),
        MAX_CASE_NAME_LENGTH,
      ),
      request: original.request,
      assertions: original.assertions,
      extractors: original.extractors,
      afterCaseId: original.id,
    });
  } catch (error) {
    return failure(error);
  }
  redirect(`/cases/${created!.id}`);
}

export async function createSchedule(suiteId: number, _: ActionState, form: FormData): Promise<ActionState> {
  const environmentId = Number(form.get("environmentId"));
  if (!Number.isSafeInteger(environmentId) || environmentId <= 0) {
    return { error: "실행할 환경을 선택해 주세요" };
  }
  return mutate("POST", "/api/schedules", {
    suiteId: id(suiteId),
    environmentId,
    cron: raw(form, "cron"),
    skipHolidays: form.get("skipHolidays") === "true",
  });
}

/** cron·환경·휴일 건너뛰기 변경. 활성 상태면 다음 실행 시각 재계산 */
export async function updateSchedule(scheduleId: number, _: ActionState, form: FormData): Promise<ActionState> {
  const environmentId = Number(form.get("environmentId"));
  if (!Number.isSafeInteger(environmentId) || environmentId <= 0) {
    return { error: "실행할 환경을 선택해 주세요" };
  }
  // 체크 해제된 상자는 폼에 없으므로 false (수정 대화상자는 항상 상자를 표시)
  return mutate("PATCH", `/api/schedules/${id(scheduleId)}`, {
    cron: raw(form, "cron"),
    environmentId,
    skipHolidays: form.get("skipHolidays") === "true",
  });
}

/** 활성·비활성 전환. 활성화 시 현재 시각 기준으로 다음 실행 시각 재계산 */
export async function setScheduleEnabled(scheduleId: number, enabled: boolean): Promise<ActionState> {
  return mutate("PATCH", `/api/schedules/${id(scheduleId)}`, { enabled: enabled === true });
}

/**
 * 여러 스케줄의 활성·휴일 건너뛰기를 한 번에 변경. 하나라도 그사이 삭제되었으면 아무것도 바뀌지 않음 (화면 재조회)
 */
export async function updateSchedules(
  scheduleIds: number[],
  change: { enabled?: boolean; skipHolidays?: boolean },
): Promise<ActionState> {
  if (
    !Array.isArray(scheduleIds) ||
    scheduleIds.length === 0 ||
    scheduleIds.length > MAX_BULK_UPDATE ||
    !scheduleIds.every((scheduleId) => Number.isSafeInteger(scheduleId) && scheduleId > 0)
  ) {
    return { error: `스케줄을 1~${MAX_BULK_UPDATE}개 골라 주세요` };
  }
  const enabled = typeof change?.enabled === "boolean" ? change.enabled : undefined;
  const skipHolidays = typeof change?.skipHolidays === "boolean" ? change.skipHolidays : undefined;
  if (enabled === undefined && skipHolidays === undefined) {
    return { error: "요청 형식이 올바르지 않습니다" };
  }
  try {
    await apiSend("PATCH", "/api/schedules", { scheduleIds, enabled, skipHolidays });
  } catch (error) {
    if (isApiError(error) && error.status === 404) {
      refresh();
      return {
        error: "고른 스케줄 중 삭제된 것이 있어 아무것도 바꾸지 않았습니다. 목록을 새로 고쳤으니 다시 골라 주세요",
      };
    }
    return failure(error);
  }
  refresh();
  return {};
}

export async function deleteSchedule(scheduleId: number): Promise<ActionState> {
  return mutate("DELETE", `/api/schedules/${id(scheduleId)}`);
}

/** 환경 점검 시간 등록. 점검 중에는 그 환경의 스케줄 실행을 건너뜀 */
export async function createMaintenanceWindow(
  environmentId: number,
  _: ActionState,
  form: FormData,
): Promise<ActionState> {
  const period = maintenanceForm(form);
  if ("error" in period) {
    return period;
  }
  return mutate("POST", "/api/maintenance-windows", { environmentId: id(environmentId), ...period });
}

/** 점검 시간 변경 (연장·단축·사유). 그사이 끝난 점검은 안내 후 화면 재조회 */
export async function updateMaintenanceWindow(windowId: number, _: ActionState, form: FormData): Promise<ActionState> {
  const period = maintenanceForm(form);
  if ("error" in period) {
    return period;
  }
  try {
    await apiSend<MaintenanceWindow>("PATCH", `/api/maintenance-windows/${id(windowId)}`, period);
  } catch (error) {
    if (isApiError(error) && error.status === 409) {
      refresh();
      return { error: "이미 끝난 점검입니다" };
    }
    return failure(error);
  }
  refresh();
  return {};
}

/** 점검 시간 삭제. 예정된 점검은 취소, 진행 중인 점검은 바로 끝나 다음 스케줄부터 실행 */
export async function deleteMaintenanceWindow(windowId: number): Promise<ActionState> {
  return mutate("DELETE", `/api/maintenance-windows/${id(windowId)}`);
}

/** 입력값(KST)을 API 요청으로. 빈 사유는 사유 없음(수정 시 삭제) */
function maintenanceForm(form: FormData): { startsAt: string; endsAt: string; reason: string } | { error: string } {
  const startsAt = fromKstInput(String(form.get("startsAt") ?? ""));
  const endsAt = fromKstInput(String(form.get("endsAt") ?? ""));
  if (!startsAt || !endsAt) {
    return { error: "시작·끝 시각을 입력해 주세요" };
  }
  const error = periodError(startsAt, endsAt, new Date());
  if (error) {
    return { error };
  }
  return { startsAt, endsAt, reason: String(form.get("reason") ?? "").trim() };
}

/**
 * 스위트 즉시 실행. 실행 완료까지 대기 후 결과 상세로 이동
 */
export async function runSuite(suiteId: number, _: ActionState, form: FormData): Promise<ActionState> {
  return startRun({ suiteId: id(suiteId) }, form);
}

/** 케이스 단건 실행. 저장된 케이스 기준이며 스위트의 다른 케이스는 실행하지 않음 */
export async function runCase(caseId: number, _: ActionState, form: FormData): Promise<ActionState> {
  return startRun({ caseId: id(caseId) }, form);
}

/** 스위트 처음부터 이 케이스까지 실행 순서대로 실행 (앞 케이스가 추출하는 값을 쓰는 케이스 확인용) */
export async function runThroughCase(caseId: number, _: ActionState, form: FormData): Promise<ActionState> {
  return startRun({ throughCaseId: id(caseId) }, form);
}

/**
 * 진행 중인 실행 중지. 진행 중이던 케이스는 끝까지 기록되고 이후 케이스는 보내지 않음. 성공 시 결과 화면 재조회
 */
export async function cancelRun(runId: number): Promise<ActionState> {
  try {
    await apiSend<RunDetail>("PATCH", `/api/runs/${id(runId)}`, { status: "CANCELED" });
  } catch (error) {
    // 이름 중복이 아닌, 그사이 실행이 끝난 경우. 화면을 다시 받아 끝난 결과를 표시
    if (isApiError(error) && error.status === 409) {
      refresh();
      return { error: "이미 끝난 실행입니다" };
    }
    return failure(error);
  }
  refresh();
  return {};
}

/**
 * 이 실행에서 판정 실패·오류가 난 케이스만 같은 환경에서 다시 실행. 그 케이스가 쓰는 추출 값을 만드는 앞 케이스는 함께 실행.
 * 시작하면 새 실행의 결과 화면으로 이동
 */
export async function rerunFailed(runId: number): Promise<ActionState> {
  let run: RunDetail | null;
  try {
    run = await apiSend<RunDetail>("POST", "/api/runs", { rerunOf: id(runId) });
  } catch (error) {
    // 이름 중복이 아닌, 다시 실행할 수 없는 상태
    if (isApiError(error) && error.status === 409) {
      return {
        error: error.detail.includes("still running")
          ? "아직 진행 중인 실행입니다. 끝난 뒤 다시 실행해 주세요"
          : "다시 실행할 실패 케이스가 없습니다. 실패한 케이스가 모두 삭제되었을 수 있습니다",
      };
    }
    return failure(error);
  }
  redirect(`/runs/${run!.id}`);
}

/** 실행이 끝나면 결과 상세로 이동 */
async function startRun(
  target: { suiteId: number } | { caseId: number } | { throughCaseId: number },
  form: FormData,
): Promise<ActionState> {
  const environmentId = Number(form.get("environmentId"));
  if (!Number.isSafeInteger(environmentId) || environmentId <= 0) {
    return { error: "실행할 환경을 선택해 주세요" };
  }
  let run: RunDetail | null;
  try {
    run = await apiSend<RunDetail>("POST", "/api/runs", { ...target, environmentId });
  } catch (error) {
    return failure(error);
  }
  redirect(`/runs/${run!.id}`);
}

async function mutate(method: "POST" | "PATCH" | "DELETE", path: string, body?: unknown): Promise<ActionState> {
  try {
    await apiSend<EnvironmentItem | SuiteItem | ProjectItem>(method, path, body);
  } catch (error) {
    return failure(error);
  }
  refresh();
  return {};
}

/** 등록용. 빈 값은 null(선택 항목 생략) */
function text(form: FormData, name: string): string | null {
  const value = form.get(name);
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

/**
 * 수정용. 폼은 모든 필드를 보내므로 빈 값도 그대로 전달. 빈 이름·URL은 Spring이 400으로 거부, 빈 설명은 설명 제거
 */
function raw(form: FormData, name: string): string {
  const value = form.get(name);
  return typeof value === "string" ? value : "";
}

function id(value: number): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error("invalid id");
  }
  return value;
}

/** 변수 편집기가 hidden input(variables)에 담은 JSON. 오류 시 메시지 반환 */
function variablesOf(form: FormData): Record<string, string> | string {
  const raw = form.get("variables");
  if (typeof raw !== "string" || raw === "") {
    return {};
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return "변수 형식이 올바르지 않습니다";
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return "변수 형식이 올바르지 않습니다";
  }
  const entries = Object.entries(parsed);
  if (entries.some(([, value]) => typeof value !== "string")) {
    return "변수 값은 문자열이어야 합니다";
  }
  return Object.fromEntries(entries) as Record<string, string>;
}

/**
 * 시크릿 편집기가 hidden input(secrets)에 담은 JSON. 값이 문자열이면 등록·교체, null이면 삭제.
 * 평문 값이므로 로그·오류 메시지에 담지 않음
 */
function secretsOf(form: FormData): Record<string, string | null> | string {
  const raw = form.get("secrets");
  if (typeof raw !== "string" || raw === "") {
    return {};
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return "시크릿 형식이 올바르지 않습니다";
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return "시크릿 형식이 올바르지 않습니다";
  }
  const entries = Object.entries(parsed);
  if (entries.some(([, value]) => value !== null && typeof value !== "string")) {
    return "시크릿 값은 문자열이어야 합니다";
  }
  // 빈 값은 의미가 없고, 복제 시 이름만 채운 행을 그대로 저장하면 조용히 빈 시크릿이 됨. 이름만 알림 (값은 알리지 않음)
  const empty = entries.find(([, value]) => value === "");
  if (empty) {
    return `시크릿 ${empty[0]}의 값을 입력하거나 그 행을 지워 주세요`;
  }
  return Object.fromEntries(entries) as Record<string, string | null>;
}

/** Spring 오류를 화면용 메시지로 변환. 그 외 오류(연결 실패 등)는 내부 정보 없이 고정 문구 */
/**
 * 수정 화면을 연 때의 version (components/catalog/opened-version). 없거나 형식이 다르면 보내지 않음 (Spring은 확인 생략)
 * 브라우저가 임의 값을 보낼 수 있지만 다르면 거부될 뿐이라 자기 저장만 막힘
 */
function versionOf(form: FormData): number | undefined {
  // 빈 문자열은 Number("")가 0이 되므로 숫자 형식부터 확인
  const raw = form.get("version");
  const value = typeof raw === "string" && /^\d+$/.test(raw) ? Number(raw) : Number.NaN;
  return Number.isSafeInteger(value) ? value : undefined;
}

/** Spring이 동시 수정 충돌(409)에 붙이는 문구 (api ApiExceptionHandler.CONCURRENT_MODIFICATION) */
const CONCURRENT_MODIFICATION = "modified by another request";

function failure(error: unknown): ActionState {
  if (isApiError(error)) {
    switch (error.status) {
      case 400:
        // 서버에 암호화 키가 없으면 시크릿 등록 자체가 거부됨. 사용자가 조치할 수 없는 설정 문제라 별도 안내
        if (error.detail.includes("secrets are disabled")) {
          return { error: "시크릿 기능이 꺼져 있습니다. 서버에 암호화 키(SENTINEL_SECRET_KEY) 설정이 필요합니다" };
        }
        // 서버가 종류(code)를 붙인 오류는 한국어 문장 (cron, JsonPath·정규식 등). 그 밖은 서버 메시지
        return {
          error: describeInputError(error.code, error.params ?? {}) ?? `입력값을 확인해 주세요: ${error.detail}`,
        };
      case 404:
        return { error: "대상을 찾을 수 없습니다. 다른 곳에서 삭제되었을 수 있습니다" };
      case 409:
        // 같은 항목을 다른 곳에서 먼저 고침 (Spring 낙관적 잠금). 이름 중복과 구분하고 최신 내용을 보이도록 화면 재조회
        if (error.detail.includes(CONCURRENT_MODIFICATION)) {
          refresh();
          return { error: "다른 곳에서 먼저 수정했습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요" };
        }
        return { error: "이미 사용 중인 이름입니다" };
      case 429:
        // 동시 수동 실행 한도 (실행은 만들어지지 않음)
        return { error: "진행 중인 실행이 많아 지금은 시작할 수 없습니다. 실행이 끝난 뒤 다시 시도해 주세요" };
    }
  }
  console.error("catalog action failed", error);
  return { error: "요청을 처리하지 못했습니다. API 서버 상태를 확인해 주세요" };
}
