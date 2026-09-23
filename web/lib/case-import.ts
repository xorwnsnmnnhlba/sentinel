import type { CaseImportCandidate, ImportCase } from "@/lib/api/types";

/** 문서 최대 길이(문자 수). Spring 한도와 동일 */
export const MAX_DOCUMENT_LENGTH = 5 * 1024 * 1024;

/** 한 번에 가져올 수 있는 케이스 수. Spring 한도와 동일 */
export const MAX_IMPORT_CASES = 500;

/** 가져올 수 있는 문서 형식. Spring CaseImportService.Format과 같은 값 */
export const IMPORT_FORMATS = {
  OPENAPI_3: { label: "OpenAPI 3 (JSON·YAML)", placeholder: "openapi: 3.0.3\ninfo: ...\npaths: ..." },
  POSTMAN: {
    label: "Postman 컬렉션 v2 (JSON)",
    placeholder: '{\n  "info": { "schema": "...collection/v2.1.0/..." },\n  "item": [...]\n}',
  },
} as const;

export type ImportFormat = keyof typeof IMPORT_FORMATS;

export function isImportFormat(value: unknown): value is ImportFormat {
  return typeof value === "string" && Object.hasOwn(IMPORT_FORMATS, value);
}

/** 미리보기 화면의 후보 행. 이름은 고칠 수 있음 */
export type CandidateRow = CaseImportCandidate & { selected: boolean };

/**
 * 후보를 화면 행으로. 스위트에 이미 있는 이름은 선택 해제 상태로 시작
 */
export function toRows(candidates: CaseImportCandidate[]): CandidateRow[] {
  return candidates.map((candidate) => ({ ...candidate, selected: !candidate.conflict }));
}

/**
 * 선택한 행의 문제. 가져오기 전에 화면에서 표시하고 버튼을 막음 (최종 판단은 Spring)
 * @returns 행 번호별 문제 문구. 선택하지 않은 행은 검사하지 않음
 */
export function rowIssues(rows: CandidateRow[], existingNames: ReadonlySet<string>): Map<number, string> {
  const issues = new Map<number, string>();
  const counts = new Map<string, number>();
  rows.forEach((row) => {
    if (row.selected) {
      const name = row.name.trim();
      counts.set(name, (counts.get(name) ?? 0) + 1);
    }
  });
  rows.forEach((row, index) => {
    if (!row.selected) {
      return;
    }
    const name = row.name.trim();
    if (name === "") {
      issues.set(index, "이름을 입력해 주세요");
    } else if (existingNames.has(name)) {
      issues.set(index, "스위트에 이미 있는 이름입니다");
    } else if ((counts.get(name) ?? 0) > 1) {
      issues.set(index, "가져올 목록 안에서 이름이 겹칩니다");
    }
  });
  return issues;
}

/** 가져오기 요청 본문. 화면 전용 속성(source·warnings·conflict·selected)은 제외하고 이름 앞뒤 공백 제거 */
export function toImportCases(rows: CandidateRow[]): ImportCase[] {
  return rows
    .filter((row) => row.selected)
    .map(({ name, request, assertions }) => ({ name: name.trim(), request, assertions }));
}

/**
 * Server Action 입력 검사. 화면을 거치지 않고 호출될 수 있으므로 형식만 확인 (값 검증은 Spring)
 */
export function isImportCase(value: unknown): value is ImportCase {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const candidate = value as Partial<ImportCase>;
  const request = candidate.request as Partial<ImportCase["request"]> | undefined;
  return (
    typeof candidate.name === "string" &&
    typeof request === "object" &&
    request !== null &&
    typeof request.method === "string" &&
    typeof request.path === "string" &&
    (candidate.assertions === undefined || Array.isArray(candidate.assertions))
  );
}
