"use client";

import { useState } from "react";

import { type KeyValueRow, KeyValueRows } from "@/components/catalog/key-value-rows";

/**
 * 환경 변수 키·값 편집. 폼 제출 시 hidden input(variables)에 JSON 객체로 전달하며 저장 시 전체 교체.
 * 키가 빈 행은 제외, 같은 키가 여러 번이면 마지막 값 사용
 */
export function VariablesEditor({ defaultValue }: { defaultValue?: Record<string, string> }) {
  const [rows, setRows] = useState<KeyValueRow[]>(() => {
    const entries = Object.entries(defaultValue ?? {}).map(([name, value]) => ({ name, value }));
    return entries.length > 0 ? entries : [{ name: "", value: "" }];
  });

  const serialized = JSON.stringify(
    Object.fromEntries(rows.filter((row) => row.name.trim() !== "").map((row) => [row.name.trim(), row.value])),
  );

  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium">
        변수 <span className="font-normal text-muted-foreground">(선택)</span>
      </legend>
      <p className="text-xs text-muted-foreground">
        경로·헤더·본문의 <code className="font-mono">{"{{이름}}"}</code>에 치환. 평문 저장이므로 토큰·API 키 입력 금지
      </p>
      <input type="hidden" name="variables" value={serialized} />
      <KeyValueRows label="변수" rows={rows} onChange={setRows} nameMaxLength={100} />
    </fieldset>
  );
}
