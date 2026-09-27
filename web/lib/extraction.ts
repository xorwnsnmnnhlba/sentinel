import type { AssertionResult } from "@/lib/api/types";

/**
 * 응답 값 추출 결과. Spring은 추출 결과를 판정 결과(assertionResults) 끝에
 * `extract {{name}} from <JsonPath | header 이름>` 설명으로 덧붙임 (api/README.md "응답 값 추출")
 */
export type ExtractionResult = {
  name: string;
  from: "BODY" | "HEADER";
  expression: string;
  passed: boolean;
  /** 추출한 값. 가림 지정이면 null, 실패면 null */
  value: string | null;
  hidden: boolean;
  /** 실패 사유. 성공이면 null */
  failure: string | null;
};

const EXTRACTION = /^extract \{\{([A-Za-z0-9_.-]+)\}\} from (header )?([\s\S]+)$/;
/** 성공 메시지 `was "값"`. Spring이 200자에서 자른 값은 닫는 따옴표 없이 `...`로 끝남 */
const EXTRACTED_VALUE = /^was "([\s\S]*?)("?)$/;
const HIDDEN = "extracted (hidden)";

/** 판정 결과를 판정 규칙과 추출 결과로 나눔. 형식이 다른 항목은 판정 규칙으로 둠 */
export function splitExtractions(results: AssertionResult[]): {
  assertions: AssertionResult[];
  extractions: ExtractionResult[];
} {
  const assertions: AssertionResult[] = [];
  const extractions: ExtractionResult[] = [];
  for (const result of results) {
    const match = EXTRACTION.exec(result.description);
    if (!match) {
      assertions.push(result);
      continue;
    }
    const hidden = result.passed && result.message === HIDDEN;
    const value = result.passed && !hidden ? extractedValue(result.message) : null;
    extractions.push({
      name: match[1],
      from: match[2] ? "HEADER" : "BODY",
      expression: match[3],
      passed: result.passed,
      value,
      hidden,
      failure: result.passed ? null : result.message,
    });
  }
  return { assertions, extractions };
}

function extractedValue(message: string | null): string | null {
  const match = message === null ? null : EXTRACTED_VALUE.exec(message);
  return match ? match[1] : message;
}
