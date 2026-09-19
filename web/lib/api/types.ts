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
  response: { statusCode: number; headers: Headers | null; body: string; bodyTruncated: boolean } | null;
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
}

export interface SuiteItem {
  id: number;
  projectId: number;
  name: string;
  description: string | null;
  caseCount: number;
}
