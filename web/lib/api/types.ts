/** Spring API 응답 타입. api/README.md "REST API" 기준 */

export type Verdict = "PASSED" | "FAILED" | "ERROR";
export type Outcome = "RESPONDED" | "ERROR";
/** 응답을 받지 못했거나 보내기 전에 실패한 이유 (api/README.md "결과 해석") */
export type ErrorReason =
  | "UNRESOLVED_VARIABLE"
  | "INVALID_REQUEST"
  | "TIMEOUT"
  | "CONNECTION_FAILED"
  | "IO_ERROR"
  | "TLS_ERROR"
  | "BLOCKED_DESTINATION"
  | "UNEXPECTED";
export type Trigger = "MANUAL" | "SCHEDULED";
/** ABORTED: 실행 중 앱 종료, CANCELED: 사용자가 중지 */
export type RunStatus = "RUNNING" | "COMPLETED" | "ABORTED" | "CANCELED";

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
  /** 여기까지 실행(스위트 처음부터 이 케이스까지)의 마지막 케이스. 그 외 실행은 null */
  throughCase: IdName | null;
  /** 실행할 케이스 수. 진행 중이면 summary.total과 비교해 진행률 표시. 이전 실행은 null */
  plannedCount: number | null;
  /** 실패 다시 실행의 원래 실행 ID. 그 외 실행은 null */
  rerunOf: number | null;
}

export interface AssertionResult {
  description: string;
  passed: boolean;
  message: string | null;
}

export type Headers = Record<string, string[]>;

export interface RunResult {
  id: number;
  testCase: IdName;
  verdict: Verdict | null;
  outcome: Outcome;
  assertionResults: AssertionResult[];
  errorReason: ErrorReason | null;
  errorMessage: string | null;
  elapsedMs: number | null;
  request: { method: string; url: string | null; headers: Headers | null; body: string | null };
  /** 보관기간이 지나 원문을 삭제한 실행은 headers·body가 null */
  response: { statusCode: number; headers: Headers | null; body: string | null; bodyTruncated: boolean } | null;
  /** 응답 JSON의 구조 지문. 원문 보관기간이 지나도 유지. JSON이 아닌 응답·응답 없음은 null */
  responseSchema: ResponseSchema | null;
  /** 같은 케이스·환경의 이전 실행 대비 구조 변화. 지문이 없으면 null */
  drift: ResultDrift | null;
  /** 직전 실행 대비 판정 변화. 비교하지 않는 실행(직전 실행 없음, 단건·여기까지·실패 다시 실행)은 null */
  change: RunChange | null;
  /** 응답을 받지 못해 다시 보낸 횟수 (api/README.md "전송 오류 재시도"). 결과는 재시도 뒤의 응답 또는 마지막 오류 */
  retries: number;
  /** 실패 다시 실행에서 원래 실행에 실패하지 않았지만 다시 실행하는 케이스가 쓰는 추출 값을 만들기 위해 함께 실행한 케이스 */
  supporting: boolean;
}

/** 직전 실행 대비 판정 변화. 실패는 판정 실패·오류 (api/README.md "직전 실행 대비 변화") */
export type RunChange = "NEW" | "NEWLY_FAILING" | "RECOVERED" | "STILL_FAILING" | "UNCHANGED";

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
  project: IdName;
  suite: IdName;
  environment: IdName;
  trigger: Trigger;
  status: RunStatus;
  startedAt: string;
  finishedAt: string | null;
  summary: RunCounts;
  results: RunResult[];
  /** 수동 실행을 시작한 사용자. 스케줄 실행·이전 기록은 null */
  triggeredBy: IdName | null;
  /** 실행을 중지한 사용자. 중지된 실행이 아니면 null (중지 시각은 finishedAt) */
  canceledBy: IdName | null;
  /** 케이스 단건 실행의 대상. 스위트 전체 실행은 null */
  testCase: IdName | null;
  /** 여기까지 실행(스위트 처음부터 이 케이스까지)의 마지막 케이스. 그 외 실행은 null */
  throughCase: IdName | null;
  /** 보관기간이 지나 요청·응답 원문(헤더·본문)을 삭제한 시각. null이면 원문 보관 중 */
  rawPurgedAt: string | null;
  /** 실행할 케이스 수. 진행 중이면 results 수와 비교해 진행률 표시. 이전 실행은 null */
  plannedCount: number | null;
  /** 비교 기준인 직전 실행 (같은 스위트·환경의 완료된 스위트 전체 실행). 없거나 스위트 전체 실행이 아니면 null */
  previousRun: { id: number; startedAt: string } | null;
  /** 실패 다시 실행의 원래 실행 ID. 그 외 실행은 null */
  rerunOf: number | null;
}

export interface SuiteItem {
  id: number;
  project: IdName;
  name: string;
  description: string | null;
  caseCount: number;
}

export type HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE" | "HEAD" | "OPTIONS";

export interface ProjectItem {
  id: number;
  name: string;
  description: string | null;
  /** 실패 알림을 프로젝트 Webhook으로 보냄. 주소는 자격증명이라 응답에 없음 */
  webhookConfigured: boolean;
}

export interface EnvironmentItem {
  id: number;
  project: IdName;
  name: string;
  baseUrl: string;
  /** 평문 저장. 토큰·API 키는 시크릿으로 등록 */
  variables: Record<string, string>;
  /** 시크릿 이름. 값은 조회 불가 (암호화 저장) */
  secretNames: string[];
  /** 수정 요청에 함께 보내면 그사이 다른 곳에서 고쳤는지 확인 (낙관적 잠금) */
  version: number;
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
  suite: IdName;
  name: string;
  request: { method: HttpMethod; path: string; headers: Headers; body: string | null };
  /** 비어 있으면 기본 판정(status in [2xx]) */
  assertions: Assertion[];
  extractors: Extractor[];
  /** 수정 요청에 함께 보내면 그사이 다른 곳에서 고쳤는지 확인 (낙관적 잠금) */
  version: number;
}

/** 스위트 정기 실행. cron은 5자리(분 시 일 월 요일), zone 기준 해석 */
export interface ScheduleDetail {
  id: number;
  /** 스위트의 프로젝트 */
  project: IdName;
  suite: IdName;
  environment: IdName;
  cron: string;
  zone: string;
  enabled: boolean;
  /** 등록한 휴일에는 실행하지 않음 */
  skipHolidays: boolean;
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

/** 대기 중인 초대 (수락·만료·취소 전). 링크(토큰)는 발급 응답에서만 볼 수 있어 없음 */
export interface PendingInvite {
  id: number;
  email: string;
  role: "ADMIN" | "MEMBER";
  createdAt: string;
  expiresAt: string;
  /** 발급한 관리자 이메일 */
  invitedBy: string | null;
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

/** 재설정 링크 확인. 새 비밀번호는 이 사용자의 {@code PUT /api/users/{userId}/password}로 */
export interface PasswordResetLinkInfo {
  /** 새 비밀번호를 설정할 사용자 {id, name(이메일)} */
  user: IdName;
  expiresAt: string;
}

/** 가져오기 경고·안내. 화면은 code·params로 한국어 문장을 만들고, 모르는 code는 message(영어) 그대로 (lib/import-message.ts) */
export interface ImportWarning {
  code: string;
  /** 문장에 넣을 값. 이름은 code마다 고정 */
  params: Record<string, string>;
  message: string;
}

/** 가져오기 후보 케이스. name·request·assertions를 그대로(또는 고쳐) 가져오기에 사용 */
export interface CaseImportCandidate {
  /** 원본 작업. 예: GET /pets/{petId} */
  source: string;
  name: string;
  request: CaseItem["request"];
  assertions: Assertion[];
  /** 변환하면서 생략·대체한 내용 */
  warnings: ImportWarning[];
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
  warnings: ImportWarning[];
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
  testCase: IdName;
  environment: IdName;
  from: string;
  to: string;
  points: ResponseTimePoint[];
}

/** 기간 전체의 케이스별 응답시간 */
export interface CaseResponseTime {
  testCase: IdName;
  samples: number;
  p50: number | null;
  p95: number | null;
  max: number | null;
}

export interface ResponseTimeSummary {
  suite: IdName;
  environment: IdName;
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
export type ChangeTargetType =
  | "PROJECT"
  | "ENVIRONMENT"
  | "SUITE"
  | "CASE"
  | "SCHEDULE"
  | "MAINTENANCE_WINDOW"
  | "HOLIDAY"
  | "ACCESS_TOKEN"
  | "USER"
  | "INVITE";

/** 휴일. 휴일 건너뛰기를 켠 스케줄은 이날 실행하지 않음 (api/README.md "휴일") */
export interface Holiday {
  id: number;
  /** YYYY-MM-DD (KST) */
  date: string;
  name: string;
}

/** 환경 점검 시간. 점검 중에는 그 환경의 스케줄 실행을 건너뜀 (api/README.md "점검 시간") */
export interface MaintenanceWindow {
  id: number;
  environment: IdName;
  project: IdName;
  /** 시작(포함)·끝(미포함) ISO-8601 */
  startsAt: string;
  endsAt: string;
  reason: string | null;
  /** 지금 점검 중. false면 예정 */
  active: boolean;
  /** 등록한 사용자. 알 수 없으면 null */
  createdBy: IdName | null;
}

export type ChangeAction = "CREATED" | "UPDATED" | "DELETED";

/** 설정 변경 1건. 값은 없고 바뀐 항목 이름만 */
export interface ChangeLogItem {
  id: number;
  occurredAt: string;
  /** 변경한 사용자 {id, name(이메일)}. 알 수 없으면 null */
  actor: IdName | null;
  /** 바뀐 항목. name은 변경 당시 이름 (스케줄은 스위트 이름, 사용자·초대는 이메일) */
  target: { type: ChangeTargetType; id: number; name: string };
  /** 소속 프로젝트 (지금 이름). 사용자·초대·휴일은 null */
  project: IdName | null;
  /** 소속 스위트. 스위트는 자신, 케이스·스케줄은 소속 스위트, 그 외는 null */
  suite: IdName | null;
  action: ChangeAction;
  /** 수정에서 값이 바뀐 항목 이름. 예: name, variables.userId, secrets.token */
  fields: string[];
}

/**
 * 불안정 케이스 (api/README.md "불안정 케이스"). 기간 안 스케줄 실행에서 실패 구간 2회 이상 또는 재시도한 실행 2회 이상
 * runs는 스위트·환경의 스케줄 실행 수, failureEpisodes는 통과(또는 기간 첫 실행)에서 실패로 바뀐 횟수
 */
export interface UnstableCase {
  testCase: IdName;
  suite: IdName;
  environment: IdName;
  runs: number;
  failures: number;
  failureEpisodes: number;
  retriedRuns: number;
}

export interface UnstableCases {
  from: string;
  to: string;
  cases: UnstableCase[];
}

/**
 * 실패 원인 (api/README.md "실패 원인"). 기간 안 스케줄 실행의 실패·오류 결과를 원인별로
 * group: NETWORK(응답 없음), API(응답은 받음), SETUP(요청 못 만듦), OTHER(Sentinel 내부 오류)
 */
export type FailureCause =
  | "TIMEOUT"
  | "CONNECTION_FAILED"
  | "CONNECTION_LOST"
  | "TLS"
  | "SERVER_ERROR"
  | "CLIENT_ERROR"
  | "ASSERTION_MISMATCH"
  | "CONFIGURATION"
  | "OTHER";

export type FailureCauseGroup = "NETWORK" | "API" | "SETUP" | "OTHER";

export interface FailureCauseCase {
  testCase: IdName;
  suite: IdName;
  environment: IdName;
  results: number;
}

export interface FailureCauseSummary {
  cause: FailureCause;
  group: FailureCauseGroup;
  results: number;
  caseCount: number;
  cases: FailureCauseCase[];
}

export interface FailureCauses {
  from: string;
  to: string;
  results: number;
  causes: FailureCauseSummary[];
}

/**
 * 지금 느린 케이스 (api/README.md "응답시간 악화 알림"). 악화 알림 후 아직 회복하지 않은 케이스·환경. 값은 감지 당시
 * increase: 늘어난 비율 (0.91은 91% 느려짐)
 */
export interface Slowdown {
  id: number;
  testCase: IdName;
  suite: IdName;
  project: IdName;
  environment: IdName;
  baselineP95: number;
  detectedP95: number;
  increase: number;
  detectedAt: string;
}

/**
 * 모니터링 공백 (api/README.md "모니터링 공백"). 스케줄이 예정 시각보다 크게 늦게 실행된 기간
 * startedAt: 늦은 스케줄 중 가장 이른 예정 시각, endedAt: 폴링 재개 시각
 */
export interface MonitoringGap {
  id: number;
  startedAt: string;
  endedAt: string;
  missedSchedules: number;
}

export interface MonitoringGaps {
  from: string;
  to: string;
  gaps: MonitoringGap[];
}

/** API 토큰 (api/README.md "API 토큰"). 원문은 발급 응답에만 있고 목록에는 앞부분(prefix)만 */
export interface AccessTokenItem {
  id: number;
  name: string;
  prefix: string;
  createdAt: string;
  expiresAt: string;
  lastUsedAt: string | null;
  expired: boolean;
}

export interface IssuedAccessToken {
  token: string;
  accessToken: AccessTokenItem;
}

/** 알림 발송 1건 (api/README.md "발송 이력"). 주소·본문은 없음 */
export interface NotificationDeliveryItem {
  id: number;
  sentAt: string;
  /** DEFAULT: 서버 기본 채널(project null), PROJECT: 프로젝트 Webhook */
  channel: "DEFAULT" | "PROJECT";
  project: IdName | null;
  /** LOG: 기본 채널 주소 미설정, 서버 로그에만 기록 */
  format: "SLACK" | "TEAMS" | "TEXT" | "LOG";
  title: string;
  tone: NotificationTone;
  /** 2xx 응답을 받음 (LOG는 true) */
  delivered: boolean;
  statusCode: number | null;
  /** 응답을 받지 못한 사유 (TIMEOUT·CONNECTION_FAILED·BLOCKED_DESTINATION 등) */
  error: string | null;
}

/** FAILURE: 실패·장애, WARNING: 악화·공백, SUCCESS: 복구·회복, INFO: 요약·확인 */
export type NotificationTone = "FAILURE" | "WARNING" | "SUCCESS" | "INFO";

/** 알림 채널: 서버 기본 채널(첫 항목)과 Webhook을 지정한 프로젝트 (api/README.md "알림 채널") */
export interface NotificationChannel {
  channel: "DEFAULT" | "PROJECT";
  /** 프로젝트 Webhook이면 그 프로젝트, 기본 채널이면 null */
  project: IdName | null;
  /** false는 기본 채널뿐 (주소가 없어 알림이 서버 로그에만 남음) */
  configured: boolean;
  /** 주소로 판별한 서식. 기본 채널 주소가 없으면 LOG, 프로젝트 주소를 읽을 수 없으면 null */
  format: NotificationDeliveryItem["format"] | null;
  /** 최근 7일 안 마지막 발송이 실패면 이어진 실패, 아니면 null */
  failing: ChannelFailure | null;
}

export interface ChannelFailure {
  /** 이어진 실패 중 가장 이른 발송 시각 */
  since: string;
  /** 마지막 성공 뒤 실패 건수 */
  failures: number;
  lastSentAt: string;
  statusCode: number | null;
  error: string | null;
}

/** 시스템 상태 (관리자). 설정은 지정 여부만, 주소·키 값은 없음 (api/README.md "시스템 상태") */
export interface SystemStatus {
  /** 빌드 정보 없이 실행하면(IDE) null */
  build: { version: string; builtAt: string } | null;
  startedAt: string | null;
  /** 적용된 마지막 DB 마이그레이션 버전 (예: "29") */
  databaseMigration: string | null;
  secrets: {
    state: SecretState;
    /** 지금 키로 풀 수 없는 값. 프로젝트 Webhook 주소는 environment·secret이 null */
    undecryptable: UndecryptableSecret[];
  };
  defaultChannel: boolean;
  resultLinks: boolean;
  heartbeat: boolean;
  retention: { rawResultDays: number; notificationDeliveryDays: number };
}

/** DISABLED: 키 없음, ENABLED: 사용 중, ROTATING: 키 교체 중 (이전 키 설정) */
export type SecretState = "DISABLED" | "ENABLED" | "ROTATING";

export interface UndecryptableSecret {
  project: IdName;
  environment: IdName | null;
  secret: string | null;
}
