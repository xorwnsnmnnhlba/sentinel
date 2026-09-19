"use client";

import { Plus, X } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

type Row = { key: string; value: string };

/**
 * 환경 변수 키·값 편집. 폼 제출 시 hidden input(variables)에 JSON 객체로 전달하며 저장 시 전체 교체.
 * 키가 빈 행은 제외, 같은 키가 여러 번이면 마지막 값 사용
 */
export function VariablesEditor({ defaultValue }: { defaultValue?: Record<string, string> }) {
  const [rows, setRows] = useState<Row[]>(() => {
    const entries = Object.entries(defaultValue ?? {}).map(([key, value]) => ({ key, value }));
    return entries.length > 0 ? entries : [{ key: "", value: "" }];
  });

  const serialized = JSON.stringify(
    Object.fromEntries(rows.filter((row) => row.key.trim() !== "").map((row) => [row.key.trim(), row.value])),
  );

  function update(index: number, patch: Partial<Row>) {
    setRows((current) => current.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  }

  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium">
        변수 <span className="font-normal text-muted-foreground">(선택)</span>
      </legend>
      <p className="text-xs text-muted-foreground">
        경로·헤더·본문의 <code className="font-mono">{"{{이름}}"}</code>에 치환. 평문 저장이므로 토큰·API 키 입력 금지
      </p>
      <input type="hidden" name="variables" value={serialized} />
      <div className="space-y-2">
        {rows.map((row, index) => (
          <div key={index} className="flex gap-2">
            <Input
              aria-label={`변수 ${index + 1} 이름`}
              placeholder="이름"
              value={row.key}
              maxLength={100}
              onChange={(event) => update(index, { key: event.target.value })}
              className="font-mono"
            />
            <Input
              aria-label={`변수 ${index + 1} 값`}
              placeholder="값"
              value={row.value}
              onChange={(event) => update(index, { value: event.target.value })}
              className="font-mono"
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label={`변수 ${index + 1} 삭제`}
              onClick={() => setRows((current) => current.filter((_, i) => i !== index))}
            >
              <X />
            </Button>
          </div>
        ))}
      </div>
      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={() => setRows((current) => [...current, { key: "", value: "" }])}
      >
        <Plus /> 변수 추가
      </Button>
    </fieldset>
  );
}
