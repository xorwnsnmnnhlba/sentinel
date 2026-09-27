"use server";

import { refresh } from "next/cache";
import { redirect } from "next/navigation";

import { apiGet, apiSend, isApiError } from "@/lib/api/server";
import type { CaseItem, EnvironmentItem, ProjectItem, RunDetail, SuiteItem } from "@/lib/api/types";
import { copyName, MAX_CASE_NAME_LENGTH } from "@/lib/copy-name";
import { type CaseDraft, isCaseDraft, validateDraft } from "@/lib/case-draft";

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
export type CaseActionState = { errors?: string[] } | undefined;

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
    saved = await apiSend<CaseItem>(method, path, { ...extra, ...validation.payload });
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
    return { errors: [`저장했지만 실행하지 못했습니다. ${reason}`.trim()] };
  }
  redirect(`/runs/${run!.id}`);
}

/**
 * 케이스 복제. 요청 정의·판정 규칙·값 추출을 그대로 복사해 같은 스위트에 등록하고 새 케이스의 수정 화면으로 이동
 * 이름은 "원본 (복사)"부터 스위트 안에서 비어 있는 이름 (Spring API 변경 없이 조회·등록 API 조합)
 */
export async function duplicateCase(caseId: number): Promise<ActionState> {
  let original: CaseItem;
  let siblings: CaseItem[];
  try {
    original = await apiGet<CaseItem>(`/api/cases/${id(caseId)}`);
    siblings = await apiGet<CaseItem[]>(`/api/cases?suiteId=${original.suiteId}`);
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
      suiteId: original.suiteId,
      name: copyName(
        original.name,
        siblings.map((sibling) => sibling.name),
        MAX_CASE_NAME_LENGTH,
      ),
      request: original.request,
      assertions: original.assertions,
      extractors: original.extractors,
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
  return mutate("POST", "/api/schedules", { suiteId: id(suiteId), environmentId, cron: raw(form, "cron") });
}

/** cron·환경 변경. 활성 상태면 다음 실행 시각 재계산 */
export async function updateSchedule(scheduleId: number, _: ActionState, form: FormData): Promise<ActionState> {
  const environmentId = Number(form.get("environmentId"));
  if (!Number.isSafeInteger(environmentId) || environmentId <= 0) {
    return { error: "실행할 환경을 선택해 주세요" };
  }
  return mutate("PATCH", `/api/schedules/${id(scheduleId)}`, { cron: raw(form, "cron"), environmentId });
}

/** 활성·비활성 전환. 활성화 시 현재 시각 기준으로 다음 실행 시각 재계산 */
export async function setScheduleEnabled(scheduleId: number, enabled: boolean): Promise<ActionState> {
  return mutate("PATCH", `/api/schedules/${id(scheduleId)}`, { enabled: enabled === true });
}

export async function deleteSchedule(scheduleId: number): Promise<ActionState> {
  return mutate("DELETE", `/api/schedules/${id(scheduleId)}`);
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
function failure(error: unknown): ActionState {
  if (isApiError(error)) {
    switch (error.status) {
      case 400:
        // 서버에 암호화 키가 없으면 시크릿 등록 자체가 거부됨. 사용자가 조치할 수 없는 설정 문제라 별도 안내
        if (error.detail.includes("secrets are disabled")) {
          return { error: "시크릿 기능이 꺼져 있습니다. 서버에 암호화 키(SENTINEL_SECRET_KEY) 설정이 필요합니다" };
        }
        return { error: `입력값을 확인해 주세요: ${error.detail}` };
      case 404:
        return { error: "대상을 찾을 수 없습니다. 다른 곳에서 삭제되었을 수 있습니다" };
      case 409:
        return { error: "이미 사용 중인 이름입니다" };
    }
  }
  console.error("catalog action failed", error);
  return { error: "요청을 처리하지 못했습니다. API 서버 상태를 확인해 주세요" };
}
