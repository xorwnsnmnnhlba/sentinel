"use client";

import { Plus, RotateCcw, X } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

type NewSecret = { name: string; value: string };

/**
 * 시크릿(토큰·API 키) 편집. 폼 제출 시 hidden input(secrets)에 JSON으로 전달하며 이름별로 반영.
 * 값이 문자열이면 등록·교체, null이면 삭제, 목록에 없는 이름은 그대로 유지.
 *
 * 저장된 값은 암호화되어 다시 볼 수 없으므로 이름만 표시하고, 값 변경은 같은 이름으로 다시 입력.
 */
export function SecretsEditor({
  savedNames = [],
  pendingNames = [],
}: {
  savedNames?: string[];
  /** 값 입력을 기다리는 새 시크릿 이름 (환경 복제: 원본의 값은 옮길 수 없어 이름만 채움) */
  pendingNames?: string[];
}) {
  const [removed, setRemoved] = useState<string[]>([]);
  const [added, setAdded] = useState<NewSecret[]>(() => pendingNames.map((name) => ({ name, value: "" })));

  const serialized = JSON.stringify({
    ...Object.fromEntries(removed.map((name) => [name, null])),
    ...Object.fromEntries(
      added.filter((secret) => secret.name.trim() !== "").map((secret) => [secret.name.trim(), secret.value]),
    ),
  });

  function update(index: number, patch: Partial<NewSecret>) {
    setAdded((current) => current.map((secret, i) => (i === index ? { ...secret, ...patch } : secret)));
  }

  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium">
        시크릿 <span className="font-normal text-muted-foreground">(선택)</span>
      </legend>
      <p className="text-xs text-muted-foreground">
        토큰·API 키. 암호화하여 저장하며 저장 후에는 값을 다시 볼 수 없음. 값을 바꾸려면 같은 이름으로 다시 입력
      </p>
      <input type="hidden" name="secrets" value={serialized} />

      {savedNames.length > 0 && (
        <ul className="space-y-1">
          {savedNames.map((name) => {
            const willRemove = removed.includes(name);
            return (
              <li key={name} className="flex items-center gap-2 text-sm">
                <span className={`font-mono ${willRemove ? "text-muted-foreground line-through" : ""}`}>{name}</span>
                <span className="text-xs text-muted-foreground">{willRemove ? "저장 시 삭제" : "저장됨"}</span>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  aria-label={willRemove ? `시크릿 ${name} 삭제 취소` : `시크릿 ${name} 삭제`}
                  onClick={() =>
                    setRemoved((current) =>
                      willRemove ? current.filter((removedName) => removedName !== name) : [...current, name],
                    )
                  }
                >
                  {willRemove ? <RotateCcw /> : <X />}
                </Button>
              </li>
            );
          })}
        </ul>
      )}

      <div className="space-y-2">
        {added.map((secret, index) => (
          <div key={index} className="flex gap-2">
            <Input
              aria-label={`새 시크릿 ${index + 1} 이름`}
              placeholder="token"
              value={secret.name}
              maxLength={100}
              onChange={(event) => update(index, { name: event.target.value })}
              className="font-mono"
            />
            <Input
              aria-label={`새 시크릿 ${index + 1} 값`}
              type="password"
              placeholder="값"
              value={secret.value}
              autoComplete="off"
              required
              onChange={(event) => update(index, { value: event.target.value })}
              className="font-mono"
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label={`새 시크릿 ${index + 1} 삭제`}
              onClick={() => setAdded((current) => current.filter((_, i) => i !== index))}
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
        onClick={() => setAdded((current) => [...current, { name: "", value: "" }])}
      >
        <Plus /> 시크릿 추가
      </Button>
    </fieldset>
  );
}
