import { describe, expect, it } from "vitest";

import { CAUSE_LABELS } from "./failure-cause";
import { describeErrorReason, ERROR_REASONS } from "./error-reason";

describe("error reason", () => {
  it("names every reason the runner records and says what to check", () => {
    // Spring TestResult.ErrorReason과 같은 목록
    expect(Object.keys(ERROR_REASONS)).toEqual([
      "UNRESOLVED_VARIABLE",
      "INVALID_REQUEST",
      "TIMEOUT",
      "CONNECTION_FAILED",
      "IO_ERROR",
      "TLS_ERROR",
      "BLOCKED_DESTINATION",
      "UNEXPECTED",
    ]);
    for (const { label, hint } of Object.values(ERROR_REASONS)) {
      expect(label).not.toBe("");
      expect(hint).not.toBe("");
    }
  });

  it("uses the same names as the failure causes on the dashboard", () => {
    expect(ERROR_REASONS.TIMEOUT.label).toBe(CAUSE_LABELS.TIMEOUT);
    expect(ERROR_REASONS.CONNECTION_FAILED.label).toBe(CAUSE_LABELS.CONNECTION_FAILED);
    expect(ERROR_REASONS.IO_ERROR.label).toBe(CAUSE_LABELS.CONNECTION_LOST);
    expect(ERROR_REASONS.TLS_ERROR.label).toBe(CAUSE_LABELS.TLS);
  });

  it("points at the certificate for a TLS failure", () => {
    expect(describeErrorReason("TLS_ERROR")).toEqual({
      label: "인증서·TLS 오류",
      hint: "대상 서버 인증서의 만료·발급자·이름을 확인해 주세요. 다시 보내도 같아 재시도하지 않습니다",
    });
  });

  it("falls back to the code for a reason it does not know", () => {
    expect(describeErrorReason("NEW_REASON")).toEqual({ label: "NEW_REASON", hint: "" });
    // 객체 기본 속성 이름을 사유로 받아도 문구로 오인하지 않음
    expect(describeErrorReason("toString")).toEqual({ label: "toString", hint: "" });
  });
});
