"use server";

import { refresh } from "next/cache";

import { apiSend, isApiError } from "@/lib/api/server";
import type { CaseImportPreview, CaseItem } from "@/lib/api/types";
import {
  type ImportFormat,
  isImportCase,
  isImportFormat,
  MAX_DOCUMENT_LENGTH,
  MAX_IMPORT_CASES,
} from "@/lib/case-import";
import { describeImportError } from "@/lib/import-message";

import type { ActionState } from "./catalog";

/**
 * 문서(OpenAPI 3, Postman 컬렉션)에서 케이스 가져오기. 미리보기로 후보를 받아 화면에서 고른 뒤 가져오기.
 *
 * 문서가 커서 Server Action 본문 한도를 next.config의 serverActions.bodySizeLimit으로 늘려 둠.
 * 화면을 거치지 않고 호출될 수 있으므로 입력 형식을 확인하고, 값 검증은 Spring(400·404·409)에 맡김
 */

export type PreviewResult = { preview?: CaseImportPreview; error?: string };

/**
 * @param document 문서 원문과 형식. 원문을 객체로 감싸 전달. React는 Server Action 인자 목록에 바로 놓인 문자열 길이를 배열 크기 한도(100만)에
 * 합산하여 1MB 안팎의 문서도 거부하므로 ("Maximum array nesting exceeded"), 한도 계산에서 빠지는 객체 속성으로 보냄
 */
export async function previewCaseImport(
  suiteId: number,
  document: { content: string; format: ImportFormat },
): Promise<PreviewResult> {
  const suite = id(suiteId);
  const content = typeof document === "object" && document !== null ? document.content : undefined;
  const format = typeof document === "object" && document !== null ? document.format : undefined;
  if (!isImportFormat(format)) {
    return { error: "문서 형식을 선택해 주세요" };
  }
  if (typeof content !== "string" || content.trim() === "") {
    return { error: "문서를 붙여넣거나 파일을 선택해 주세요" };
  }
  if (content.length > MAX_DOCUMENT_LENGTH) {
    return { error: "문서는 5MB까지 가져올 수 있습니다" };
  }
  try {
    const preview = await apiSend<CaseImportPreview>("POST", "/api/case-import-previews", {
      suiteId: suite,
      format,
      content,
    });
    return { preview: preview! };
  } catch (error) {
    return { error: failure(error, "문서를 읽지 못했습니다") };
  }
}

/**
 * 고른 케이스를 한 번에 등록. 한 건이라도 실패하면 전부 등록되지 않음. 성공 시 스위트 화면 재조회
 */
export async function importCases(suiteId: number, cases: unknown): Promise<ActionState> {
  const suite = id(suiteId);
  if (!Array.isArray(cases) || cases.length === 0) {
    return { error: "가져올 케이스를 선택해 주세요" };
  }
  if (cases.length > MAX_IMPORT_CASES) {
    return { error: `한 번에 ${MAX_IMPORT_CASES}건까지 가져올 수 있습니다` };
  }
  if (!cases.every(isImportCase)) {
    return { error: "요청 형식이 올바르지 않습니다" };
  }
  try {
    await apiSend<{ suiteId: number; cases: CaseItem[] }>("POST", "/api/case-imports", {
      suiteId: suite,
      cases: cases.map(({ name, request, assertions }) => ({ name, request, assertions })),
    });
  } catch (error) {
    return { error: failure(error, "가져오지 못했습니다") };
  }
  refresh();
  return {};
}

function id(value: number): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error("invalid id");
  }
  return value;
}

/** Spring 오류를 화면 문구로. 어느 문서·케이스가 문제인지 알 수 있도록 detail을 함께 표시 */
function failure(error: unknown, prefix: string): string {
  if (isApiError(error)) {
    switch (error.status) {
      case 400:
        // 가져올 수 없는 문서는 서버가 붙인 code로 한국어 문장. 그 밖의 400(잘못된 케이스 등)은 서버 메시지
        return `${prefix}: ${describeImportError(error.code, error.params ?? {}, error.detail)}`;
      case 404:
        return "스위트를 찾을 수 없습니다. 다른 곳에서 삭제되었을 수 있습니다";
      case 409:
        return `이미 사용 중인 이름이 있습니다: ${error.detail}`;
    }
  }
  console.error("case import failed", error);
  return "요청을 처리하지 못했습니다. API 서버 상태를 확인해 주세요";
}
