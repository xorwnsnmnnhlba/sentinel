"use client";

import { Plus, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export type KeyValueRow = { name: string; value: string };

/**
 * 이름·값 행 목록 편집 (환경 변수, 요청 헤더). 상태는 부모가 관리
 * @param label 접근성 이름 접두어. 예: "변수" → "변수 1 이름", "변수 추가"
 */
export function KeyValueRows({
  label,
  rows,
  onChange,
  nameMaxLength,
  namePlaceholder = "이름",
  valuePlaceholder = "값",
}: {
  label: string;
  rows: KeyValueRow[];
  onChange: (rows: KeyValueRow[]) => void;
  nameMaxLength?: number;
  namePlaceholder?: string;
  valuePlaceholder?: string;
}) {
  function update(index: number, patch: Partial<KeyValueRow>) {
    onChange(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  }

  return (
    <div className="space-y-2">
      {rows.map((row, index) => (
        <div key={index} className="flex gap-2">
          <Input
            aria-label={`${label} ${index + 1} 이름`}
            placeholder={namePlaceholder}
            value={row.name}
            maxLength={nameMaxLength}
            onChange={(event) => update(index, { name: event.target.value })}
            className="font-mono"
          />
          <Input
            aria-label={`${label} ${index + 1} 값`}
            placeholder={valuePlaceholder}
            value={row.value}
            onChange={(event) => update(index, { value: event.target.value })}
            className="font-mono"
          />
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label={`${label} ${index + 1} 삭제`}
            onClick={() => onChange(rows.filter((_, i) => i !== index))}
          >
            <X />
          </Button>
        </div>
      ))}
      <Button type="button" variant="outline" size="sm" onClick={() => onChange([...rows, { name: "", value: "" }])}>
        <Plus /> {label} 추가
      </Button>
    </div>
  );
}
