"use client";

import { Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState, useTransition } from "react";

import { KeyValueRows } from "@/components/catalog/key-value-rows";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import type { CaseActionState } from "@/lib/actions/catalog";
import type { HttpMethod } from "@/lib/api/types";
import {
  ASSERTION_LABELS,
  ASSERTION_TYPES,
  type AssertionDraft,
  type AssertionType,
  type CaseDraft,
  HEADER_OPERATORS,
  HTTP_METHODS,
  needsValue,
  newAssertion,
  type Operator,
  OPERATOR_LABELS,
  OPERATORS,
  validateDraft,
} from "@/lib/case-draft";
import { prettyBody } from "@/lib/format";

/**
 * 케이스 등록·수정 폼. 저장 전 화면에서 먼저 검증하고, Server Action이 같은 규칙으로 재검사한 뒤 Spring에 저장
 */
export function CaseEditor({
  initial,
  action,
  cancelHref,
  submitLabel,
}: {
  initial: CaseDraft;
  action: (draft: CaseDraft) => Promise<CaseActionState>;
  cancelHref: string;
  submitLabel: string;
}) {
  const [draft, setDraft] = useState(initial);
  const [errors, setErrors] = useState<string[]>([]);
  const [newType, setNewType] = useState<AssertionType>("statusCode");
  const [pending, startTransition] = useTransition();

  function update(patch: Partial<CaseDraft>) {
    setDraft((current) => ({ ...current, ...patch }));
  }

  function updateAssertion(index: number, next: AssertionDraft) {
    update({ assertions: draft.assertions.map((assertion, i) => (i === index ? next : assertion)) });
  }

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validation = validateDraft(draft);
    if (!validation.ok) {
      setErrors(validation.errors);
      return;
    }
    setErrors([]);
    startTransition(async () => {
      const result = await action(draft);
      if (result?.errors?.length) {
        setErrors(result.errors);
      }
    });
  }

  return (
    <form method="post" onSubmit={submit} className="space-y-8" noValidate>
      <section className="space-y-2">
        <Label htmlFor="case-name">이름</Label>
        <Input
          id="case-name"
          value={draft.name}
          maxLength={200}
          placeholder="결제 내역 조회"
          onChange={(event) => update({ name: event.target.value })}
        />
      </section>

      <section className="space-y-4">
        <h2 className="text-lg font-semibold">요청</h2>
        <div className="flex gap-2">
          <NativeSelect
            aria-label="메서드"
            value={draft.method}
            onChange={(event) => update({ method: event.target.value as HttpMethod })}
            className="w-32"
          >
            {HTTP_METHODS.map((method) => (
              <NativeSelectOption key={method} value={method}>
                {method}
              </NativeSelectOption>
            ))}
          </NativeSelect>
          <Input
            aria-label="경로"
            aria-describedby="case-path-hint"
            value={draft.path}
            maxLength={2000}
            placeholder="/billing/v1/history?userId={{userId}}"
            onChange={(event) => update({ path: event.target.value })}
            className="font-mono"
          />
        </div>
        <p id="case-path-hint" className="text-xs text-muted-foreground">
          환경의 기본 URL 뒤에 붙는 경로. / 또는 <code className="font-mono">{"{{변수}}"}</code>로 시작.
          경로·헤더·본문에 변수 사용 가능 (변수로 시작하면 치환 결과가 /로 시작해야 함)
        </p>

        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">
            헤더 <span className="font-normal text-muted-foreground">(선택)</span>
          </legend>
          <KeyValueRows
            label="헤더"
            rows={draft.headers}
            onChange={(headers) => update({ headers })}
            nameMaxLength={200}
            namePlaceholder="Accept"
            valuePlaceholder="application/json"
          />
        </fieldset>

        <div className="space-y-2">
          <div className="flex items-end justify-between">
            <Label htmlFor="case-body">
              본문 <span className="font-normal text-muted-foreground">(선택)</span>
            </Label>
            <Button type="button" variant="ghost" size="sm" onClick={() => update({ body: prettyBody(draft.body) })}>
              JSON 정렬
            </Button>
          </div>
          <Textarea
            id="case-body"
            rows={8}
            value={draft.body}
            placeholder='{"userId": "{{userId}}", "amount": 1000}'
            onChange={(event) => update({ body: event.target.value })}
            className="font-mono text-xs"
          />
        </div>
      </section>

      <section className="space-y-4">
        <div>
          <h2 className="text-lg font-semibold">판정 규칙</h2>
          <p className="text-sm text-muted-foreground">
            모든 규칙을 통과하면 통과. 규칙이 없으면 상태 코드 2xx를 통과로 판정
          </p>
        </div>
        {draft.assertions.map((assertion, index) => (
          <div key={index} className="space-y-3 rounded-lg border p-4">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-medium">
                {index + 1}. {ASSERTION_LABELS[assertion.type]}
              </h3>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={`판정 규칙 ${index + 1} 삭제`}
                onClick={() => update({ assertions: draft.assertions.filter((_, i) => i !== index) })}
              >
                <Trash2 />
              </Button>
            </div>
            <AssertionFields
              label={`판정 규칙 ${index + 1}`}
              assertion={assertion}
              onChange={(next) => updateAssertion(index, next)}
            />
          </div>
        ))}
        <div className="flex gap-2">
          <NativeSelect
            aria-label="추가할 판정 규칙"
            value={newType}
            onChange={(event) => setNewType(event.target.value as AssertionType)}
          >
            {ASSERTION_TYPES.map((type) => (
              <NativeSelectOption key={type} value={type}>
                {ASSERTION_LABELS[type]}
              </NativeSelectOption>
            ))}
          </NativeSelect>
          <Button
            type="button"
            variant="outline"
            onClick={() => update({ assertions: [...draft.assertions, newAssertion(newType)] })}
          >
            <Plus /> 규칙 추가
          </Button>
        </div>
      </section>

      {errors.length > 0 && (
        <ul role="alert" className="space-y-1 rounded-md bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {errors.map((error) => (
            <li key={error}>{error}</li>
          ))}
        </ul>
      )}

      <div className="flex gap-2">
        <Button type="submit" disabled={pending}>
          {pending ? "저장 중…" : submitLabel}
        </Button>
        <Button asChild variant="outline">
          <Link href={cancelHref}>취소</Link>
        </Button>
      </div>
    </form>
  );
}

function AssertionFields({
  label,
  assertion,
  onChange,
}: {
  label: string;
  assertion: AssertionDraft;
  onChange: (next: AssertionDraft) => void;
}) {
  switch (assertion.type) {
    case "statusCode":
      return (
        <Field label={`${label} 상태 코드`} hint="쉼표로 구분. x는 임의의 숫자 (예: 200, 2xx, 40x)">
          {(props) => (
            <Input
              {...props}
              value={assertion.anyOf}
              placeholder="200, 2xx"
              onChange={(event) => onChange({ ...assertion, anyOf: event.target.value })}
              className="font-mono"
            />
          )}
        </Field>
      );
    case "responseTime":
      return (
        <Field label={`${label} 최대 응답 시간(ms)`} hint="이 시간 이하면 통과">
          {(props) => (
            <Input
              {...props}
              type="number"
              min={1}
              value={assertion.maxMillis}
              onChange={(event) => onChange({ ...assertion, maxMillis: event.target.value })}
              className="w-40"
            />
          )}
        </Field>
      );
    case "header":
      return (
        <div className="grid gap-2 sm:grid-cols-[1fr_auto_1fr]">
          <Input
            aria-label={`${label} 헤더 이름`}
            value={assertion.name}
            placeholder="Content-Type"
            onChange={(event) => onChange({ ...assertion, name: event.target.value })}
            className="font-mono"
          />
          <OperatorSelect
            label={`${label} 비교`}
            operators={HEADER_OPERATORS}
            value={assertion.operator}
            onChange={(operator) => onChange({ ...assertion, operator })}
          />
          <Input
            aria-label={`${label} 값`}
            value={assertion.value}
            disabled={!needsValue(assertion.operator)}
            placeholder={needsValue(assertion.operator) ? "json" : "값 불필요"}
            onChange={(event) => onChange({ ...assertion, value: event.target.value })}
            className="font-mono"
          />
        </div>
      );
    case "jsonPath":
      return (
        <div className="space-y-2">
          <div className="grid gap-2 sm:grid-cols-[1fr_auto_1fr]">
            <Input
              aria-label={`${label} JsonPath`}
              value={assertion.path}
              placeholder="$.items[0].id"
              onChange={(event) => onChange({ ...assertion, path: event.target.value })}
              className="font-mono"
            />
            <OperatorSelect
              label={`${label} 비교`}
              operators={[...OPERATORS]}
              value={assertion.operator}
              onChange={(operator) => onChange({ ...assertion, operator })}
            />
            <Input
              aria-label={`${label} 기대값`}
              value={assertion.expected}
              disabled={!needsValue(assertion.operator)}
              placeholder={needsValue(assertion.operator) ? "1000" : "값 불필요"}
              onChange={(event) => onChange({ ...assertion, expected: event.target.value })}
              className="font-mono"
            />
          </div>
          <p className="text-xs text-muted-foreground">
            기대값은 JSON으로 해석. <code className="font-mono">1000</code>·<code className="font-mono">true</code>는
            숫자·불리언, 문자열 숫자는 <code className="font-mono">&quot;1000&quot;</code>처럼 따옴표 사용. 그 외 입력은
            문자열
          </p>
        </div>
      );
    case "bodyContains":
      return (
        <Field label={`${label} 포함할 문자열`} hint="대소문자 구분">
          {(props) => (
            <Input
              {...props}
              value={assertion.text}
              placeholder="OK"
              onChange={(event) => onChange({ ...assertion, text: event.target.value })}
              className="font-mono"
            />
          )}
        </Field>
      );
  }
}

/** 입력 칸 하나와 안내 문구. 접근성 이름에 규칙 번호를 포함하여 여러 규칙의 입력 칸 구분 */
function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint: string;
  children: (props: { "aria-label": string }) => React.ReactNode;
}) {
  return (
    <div className="space-y-1">
      {children({ "aria-label": label })}
      <p className="text-xs text-muted-foreground">{hint}</p>
    </div>
  );
}

function OperatorSelect({
  label,
  operators,
  value,
  onChange,
}: {
  label: string;
  operators: Operator[];
  value: Operator;
  onChange: (operator: Operator) => void;
}) {
  return (
    <NativeSelect aria-label={label} value={value} onChange={(event) => onChange(event.target.value as Operator)}>
      {operators.map((operator) => (
        <NativeSelectOption key={operator} value={operator}>
          {OPERATOR_LABELS[operator]}
        </NativeSelectOption>
      ))}
    </NativeSelect>
  );
}
