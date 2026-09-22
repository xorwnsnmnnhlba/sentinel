/** Spring API 응답 타입. api/README.md "REST API" 기준 */

export type Verdict = "PASSED" | "FAILED" | "ERROR";
export type Outcome = "RESPONDED" | "ERROR";
export type Trigger = "MANUAL" | "SCHEDULED";
export type RunStatus = "RUNNING" | "COMPLETED" | "ABORTED";

export interface IdName {
  id: number;
  name: string;
}

export interface Page<T> {
  content: T[];
  /** 0부터 시작 */
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface RunCounts {
  total: number;
  passed: number;
  failed: number;
  errors: number;
}

export interface RunSummary {
  id: number;
  project: IdName;
  suite: IdName;
  environment: IdName;
  trigger: Trigger;
  status: RunStatus;
  startedAt: string;
  finishedAt: string | null;
  summary: RunCounts;
  /** 수동 실행을 시작한 사용자. 스케줄 실행·이전 기록은 null */
  triggeredBy: IdName | null;
}

export interface AssertionResult {
  description: string;
  passed: boolean;
  message: string | null;
}

export type Headers = Record<string, string[]>;

export interface RunResult {
  id: number;
  testCaseId: number;
  testCaseName: string;
  verdict: Verdict | null;
  outcome: Outcome;
  assertionResults: AssertionResult[];
  errorReason: string | null;
  errorMessage: string | null;
  elapsedMs: number | null;
  request: { method: string; url: string | null; headers: Headers | null; body: string | null };
  /** 보관기간이 지나 원문을 삭제한 실행은 headers·body가 null */
  response: { statusCode: number; headers: Headers | null; body: string | null; bodyTruncated: boolean } | null;
}

export interface RunDetail {
  id: number;
  suiteId: number;
  suiteName: string;
  environmentId: number;
  environmentName: string;
  trigger: Trigger;
  status: RunStatus;
  startedAt: string;
  finishedAt: string | null;
  summary: RunCounts;
  results: RunResult[];
  /** 수동 실행을 시작한 사용자. 스케줄 실행·이전 기록은 null */
  triggeredBy: IdName | null;
  /** 보관기간이 지나 요청·응답 원문(헤더·본문)을 삭제한 시각. null이면 원문 보관 중 */
  rawPurgedAt: string | null;
}

export interface SuiteItem {
  id: number;
  projectId: number;
  name: string;
  description: string | null;
  caseCount: number;
}

export type HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE" | "HEAD" | "OPTIONS";

export interface ProjectItem {
  id: number;
  name: string;
  description: string | null;
}

export interface EnvironmentItem {
  id: number;
  projectId: number;
  name: string;
  baseUrl: string;
  /** 평문 저장. 토큰·API 키는 시크릿으로 등록 */
  variables: Record<string, string>;
  /** 시크릿 이름. 값은 조회 불가 (암호화 저장) */
  secretNames: string[];
}

/** 판정 규칙. type별 필드는 api/README.md "판정 규칙 (assertions)" 참고 */
export type Assertion = { type: string } & Record<string, unknown>;

export interface CaseItem {
  id: number;
  suiteId: number;
  name: string;
  request: { method: HttpMethod; path: string; headers: Headers; body: string | null };
  /** 비어 있으면 기본 판정(status in [2xx]) */
  assertions: Assertion[];
}

/** 스위트 정기 실행. cron은 5자리(분 시 일 월 요일), zone 기준 해석 */
export interface ScheduleDetail {
  id: number;
  suiteId: number;
  environmentId: number;
  cron: string;
  zone: string;
  enabled: boolean;
  /** 비활성 시 null */
  nextRunAt: string | null;
  lastTriggeredAt: string | null;
}

/** 로그인 사용자. 비밀번호 등 민감한 값은 포함하지 않음 */
export interface AuthenticatedUser {
  id: number;
  email: string;
  name: string;
  role: "ADMIN" | "MEMBER";
}

export interface UserSummary {
  id: number;
  email: string;
  name: string;
  role: "ADMIN" | "MEMBER";
  createdAt: string;
}

/** 초대·재설정 링크 발급 결과. token은 발급 응답에서만 확인 가능 */
export interface IssuedLink {
  token: string;
  expiresAt: string;
}

/** 링크 확인 결과 */
export interface LinkInfo {
  email: string;
  expiresAt: string;
}
