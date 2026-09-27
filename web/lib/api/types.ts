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
  /** 케이스 단건 실행의 대상. 스위트 전체 실행은 null */
  testCase: IdName | null;
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
  /** 응답 JSON의 구조 지문. 원문 보관기간이 지나도 유지. JSON이 아닌 응답·응답 없음은 null */
  responseSchema: ResponseSchema | null;
  /** 같은 케이스·환경의 이전 실행 대비 구조 변화. 지문이 없으면 null */
  drift: ResultDrift | null;
}

export type DriftLevel = "BREAKING" | "WARNING" | "INFO";

export interface DriftChange {
  level: DriftLevel;
  /** REMOVED 필수 필드 없음, TYPE_CHANGED null 외 타입 변경, NULLABLE null 등장, ADDED 새 필드 */
  kind: "REMOVED" | "TYPE_CHANGED" | "NULLABLE" | "ADDED";
  path: string;
  expected: string[];
  actual: string[];
}

/** COLLECTING이면 이전 실행이 적어 비교하지 않음 (changes 빈 목록) */
export interface ResultDrift {
  status: "COMPARED" | "COLLECTING";
  observations: number;
  changes: DriftChange[];
}

/**
 * 응답 구조 지문. 경로($.items[].id) → 관측 타입(object·array·string·number·boolean·null)
 * complete가 false면 깊이(20)·경로 수(1,000) 한도로 일부만 기록
 */
export interface ResponseSchema {
  fields: Record<string, string[]>;
  complete: boolean;
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
  /** 케이스 단건 실행의 대상. 스위트 전체 실행은 null */
  testCase: IdName | null;
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

/**
 * 응답 값 추출 규칙. 추출한 값은 같은 스위트 실행에서 뒤 케이스가 {{name}}으로 사용 (api/README.md "응답 값 추출")
 * expression: BODY는 JsonPath, HEADER는 헤더 이름. secret이면 실행 이력에서 값을 가림
 */
export interface Extractor {
  name: string;
  from: "BODY" | "HEADER";
  expression: string;
  secret: boolean;
}

export interface CaseItem {
  id: number;
  suiteId: number;
  name: string;
  request: { method: HttpMethod; path: string; headers: Headers; body: string | null };
  /** 비어 있으면 기본 판정(status in [2xx]) */
  assertions: Assertion[];
  extractors: Extractor[];
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

/** 가져오기 후보 케이스. name·request·assertions를 그대로(또는 고쳐) 가져오기에 사용 */
export interface CaseImportCandidate {
  /** 원본 작업. 예: GET /pets/{petId} */
  source: string;
  name: string;
  request: CaseItem["request"];
  assertions: Assertion[];
  /** 변환하면서 생략·대체한 내용 */
  warnings: string[];
  /** 스위트에 같은 이름의 케이스가 있음 */
  conflict: boolean;
}

/** OpenAPI 문서 미리보기. 저장되지 않음 */
export interface CaseImportPreview {
  title: string | null;
  version: string | null;
  /** 문서의 서버 주소. 환경 기본 URL 설정 참고용 */
  servers: string[];
  cases: CaseImportCandidate[];
  /** 문서 단위 경고 (지원하지 않아 제외한 작업 등) */
  warnings: string[];
}

/** 가져오기 요청의 케이스 */
export type ImportCase = Pick<CaseImportCandidate, "name" | "request" | "assertions">;

/** 하루 응답시간. 응답을 받은 결과가 없으면 samples 0, 값 null */
export interface ResponseTimePoint {
  /** KST 날짜 (YYYY-MM-DD) */
  date: string;
  samples: number;
  p50: number | null;
  p95: number | null;
  max: number | null;
}

/** 케이스 1건의 일별 응답시간 */
export interface ResponseTimeTrend {
  caseId: number;
  environmentId: number;
  from: string;
  to: string;
  points: ResponseTimePoint[];
}

/** 기간 전체의 케이스별 응답시간 */
export interface CaseResponseTime {
  caseId: number;
  name: string;
  samples: number;
  p50: number | null;
  p95: number | null;
  max: number | null;
}

export interface ResponseTimeSummary {
  suiteId: number;
  environmentId: number;
  from: string;
  to: string;
  cases: CaseResponseTime[];
}

/** 스위트별 실행 요약 (대시보드) */
export interface SuiteSummary {
  suite: IdName;
  project: IdName;
  /** 기간 안에 시작한 실행 수 */
  runs: number;
  /** 그중 판정 실패·오류 결과가 있는 실행 수 */
  failingRuns: number;
  /** 기간 안 실행의 판정별 결과 수 */
  results: RunCounts;
  /** 통과 결과 비율(0~1). 결과가 없으면 null */
  passRate: number | null;
  /** 기간과 무관한 가장 최근 실행 */
  lastRun: { id: number; startedAt: string; status: RunStatus; summary: RunCounts } | null;
}

export interface SuiteSummaries {
  from: string;
  to: string;
  suites: SuiteSummary[];
}

/** 케이스·환경별 p95 변화 (이전 같은 길이 기간 대비) */
export interface ResponseTimeChange {
  testCase: IdName;
  suite: IdName;
  environment: IdName;
  previousP95: number;
  currentP95: number;
  /** 증가율. 0.42는 42% 느려짐, 음수는 빨라짐 */
  change: number;
  previousSamples: number;
  currentSamples: number;
}

export interface ResponseTimeChanges {
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  changes: ResponseTimeChange[];
}

/** 변경 이력 대상 (api/README.md "변경 이력") */
export type ChangeTargetType = "PROJECT" | "ENVIRONMENT" | "SUITE" | "CASE" | "SCHEDULE" | "USER" | "INVITE";

export type ChangeAction = "CREATED" | "UPDATED" | "DELETED";

/** 설정 변경 1건. 값은 없고 바뀐 항목 이름만 */
export interface ChangeLogItem {
  id: number;
  occurredAt: string;
  /** 변경한 사용자 {id, name(이메일)}. 알 수 없으면 null */
  actor: IdName | null;
  targetType: ChangeTargetType;
  targetId: number;
  /** 변경 당시 이름 (스케줄은 스위트 이름, 사용자·초대는 이메일) */
  targetName: string;
  /** 사용자·초대는 null */
  projectId: number | null;
  action: ChangeAction;
  /** 수정에서 값이 바뀐 항목 이름. 예: name, variables.userId, secrets.token */
  fields: string[];
}
