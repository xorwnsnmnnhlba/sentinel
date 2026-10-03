"use client";

import { FileUp } from "lucide-react";
import { useMemo, useState, useTransition } from "react";

import { FormError } from "@/components/catalog/form-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { importCases, previewCaseImport } from "@/lib/actions/case-import";
import type { CaseImportPreview } from "@/lib/api/types";
import {
  type CandidateRow,
  IMPORT_FORMATS,
  type ImportFormat,
  isImportFormat,
  MAX_DOCUMENT_LENGTH,
  MAX_IMPORT_CASES,
  rowIssues,
  toImportCases,
  toRows,
} from "@/lib/case-import";

/**
 * 문서(OpenAPI 3, Postman 컬렉션)에서 케이스 가져오기. 문서 입력 → 미리보기(후보 선택·이름 수정) → 가져오기.
 * 닫았다 다시 열면 처음 단계부터 시작하도록 내용을 새로 마운트
 */
export function CaseImportDialog({ suiteId, existingNames }: { suiteId: number; existingNames: string[] }) {
  const [open, setOpen] = useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="ghost" size="sm">
          <FileUp /> 문서 가져오기
        </Button>
      </DialogTrigger>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>문서에서 케이스 가져오기</DialogTitle>
          <DialogDescription>
            OpenAPI 3 문서나 Postman 컬렉션을 붙여넣거나 파일을 선택합니다. 미리보기에서 가져올 케이스를 고릅니다.
          </DialogDescription>
        </DialogHeader>
        {open && <ImportSteps suiteId={suiteId} existingNames={existingNames} onDone={() => setOpen(false)} />}
      </DialogContent>
    </Dialog>
  );
}

function ImportSteps({
  suiteId,
  existingNames,
  onDone,
}: {
  suiteId: number;
  existingNames: string[];
  onDone: () => void;
}) {
  // 문서는 미리보기 후 '문서 다시 선택'으로 돌아가도 유지
  const [content, setContent] = useState("");
  const [format, setFormat] = useState<ImportFormat>("OPENAPI_3");
  const [preview, setPreview] = useState<CaseImportPreview | null>(null);

  return preview ? (
    <CandidateStep
      suiteId={suiteId}
      preview={preview}
      existingNames={existingNames}
      onBack={() => setPreview(null)}
      onDone={onDone}
    />
  ) : (
    <DocumentStep
      suiteId={suiteId}
      format={format}
      onFormatChange={setFormat}
      content={content}
      onChange={setContent}
      onPreview={setPreview}
    />
  );
}

function DocumentStep({
  suiteId,
  format,
  onFormatChange,
  content,
  onChange,
  onPreview,
}: {
  suiteId: number;
  format: ImportFormat;
  onFormatChange: (format: ImportFormat) => void;
  content: string;
  onChange: (content: string) => void;
  onPreview: (preview: CaseImportPreview) => void;
}) {
  const [error, setError] = useState<string>();
  const [pending, startTransition] = useTransition();

  async function readFile(file: File | undefined) {
    if (!file) {
      return;
    }
    // 문자 수 한도지만 파일 크기로 먼저 걸러 큰 파일을 읽지 않음 (UTF-8에서 바이트 수 ≥ 문자 수)
    if (file.size > MAX_DOCUMENT_LENGTH * 4) {
      setError("문서는 5MB까지 가져올 수 있습니다");
      return;
    }
    setError(undefined);
    onChange(await file.text());
  }

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (content.length > MAX_DOCUMENT_LENGTH) {
      setError("문서는 5MB까지 가져올 수 있습니다");
      return;
    }
    startTransition(async () => {
      const result = await send(() => previewCaseImport(suiteId, { content, format }));
      if (result.preview) {
        onPreview(result.preview);
      } else {
        setError(result.error);
      }
    });
  }

  return (
    <form method="post" onSubmit={submit} className="space-y-4">
      <div className="space-y-2">
        <Label htmlFor="import-format">형식</Label>
        <NativeSelect
          id="import-format"
          value={format}
          onChange={(event) => isImportFormat(event.target.value) && onFormatChange(event.target.value)}
        >
          {Object.entries(IMPORT_FORMATS).map(([value, { label }]) => (
            <NativeSelectOption key={value} value={value}>
              {label}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </div>
      <div className="space-y-2">
        <Label htmlFor="import-file">파일</Label>
        <Input
          id="import-file"
          type="file"
          accept=".json,.yaml,.yml,application/json,application/yaml"
          onChange={(event) => readFile(event.target.files?.[0])}
        />
      </div>
      <div className="space-y-2">
        <Label htmlFor="import-content">문서</Label>
        <Textarea
          id="import-content"
          value={content}
          onChange={(event) => onChange(event.target.value)}
          placeholder={IMPORT_FORMATS[format].placeholder}
          className="h-64 font-mono text-xs"
          spellCheck={false}
        />
      </div>
      <FormError message={error} />
      <DialogFooter>
        <Button type="submit" disabled={pending || content.trim() === ""}>
          {pending ? "읽는 중…" : "미리보기"}
        </Button>
      </DialogFooter>
    </form>
  );
}

function CandidateStep({
  suiteId,
  preview,
  existingNames,
  onBack,
  onDone,
}: {
  suiteId: number;
  preview: CaseImportPreview;
  existingNames: string[];
  onBack: () => void;
  onDone: () => void;
}) {
  const [rows, setRows] = useState<CandidateRow[]>(() => toRows(preview.cases));
  const [error, setError] = useState<string>();
  const [pending, startTransition] = useTransition();
  const existing = useMemo(() => new Set(existingNames), [existingNames]);
  const issues = rowIssues(rows, existing);
  const selectedCount = rows.filter((row) => row.selected).length;
  const allSelected = selectedCount === rows.length;

  function update(index: number, change: Partial<CandidateRow>) {
    setRows((current) => current.map((row, i) => (i === index ? { ...row, ...change } : row)));
  }

  function submit() {
    startTransition(async () => {
      const result = await send(() => importCases(suiteId, toImportCases(rows)));
      if (result?.error) {
        setError(result.error);
      } else {
        onDone();
      }
    });
  }

  return (
    <div className="space-y-4">
      <div className="space-y-1 text-sm">
        <p className="font-medium">
          {[preview.title ?? "제목 없음", preview.version].filter(Boolean).join(" · ")}
          <span className="ml-2 font-normal text-muted-foreground">후보 {rows.length}건</span>
        </p>
        {preview.servers.length > 0 && (
          <p className="text-muted-foreground">
            문서의 서버 주소: <span className="font-mono text-xs">{preview.servers.join(", ")}</span>. 기본 URL은
            환경에서 설정합니다
          </p>
        )}
        <Warnings items={preview.warnings} />
      </div>

      <div className="flex items-center justify-between text-sm">
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={allSelected}
            onChange={(event) =>
              setRows((current) => current.map((row) => ({ ...row, selected: event.target.checked })))
            }
            className="size-4 accent-primary"
          />
          전체 선택
        </label>
        <span className="text-muted-foreground">{selectedCount}건 선택</span>
      </div>

      <ul className="max-h-[45vh] divide-y overflow-y-auto rounded-lg border bg-card">
        {rows.map((row, index) => {
          const issue = issues.get(index);
          return (
            // source는 Postman 컬렉션에서 겹칠 수 있어 순서로 구분 (행은 추가·삭제·정렬되지 않음)
            <li key={index} className="space-y-2 p-3">
              <div className="flex items-center gap-3">
                <input
                  type="checkbox"
                  checked={row.selected}
                  onChange={(event) => update(index, { selected: event.target.checked })}
                  aria-label={`${row.source} 가져오기`}
                  className="size-4 shrink-0 accent-primary"
                />
                <Badge variant="outline" className="shrink-0 font-mono">
                  {row.request.method}
                </Badge>
                <span className="min-w-0 font-mono text-xs [overflow-wrap:anywhere]">{row.request.path}</span>
              </div>
              <div className="pl-7">
                <Input
                  value={row.name}
                  onChange={(event) => update(index, { name: event.target.value })}
                  aria-label={`${row.source} 이름`}
                  aria-invalid={issue !== undefined}
                  maxLength={200}
                  disabled={!row.selected}
                />
                {issue && <p className="mt-1 text-xs text-destructive">{issue}</p>}
                <Warnings items={row.warnings} />
              </div>
            </li>
          );
        })}
      </ul>

      <FormError message={error} />
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onBack} disabled={pending}>
          문서 다시 선택
        </Button>
        <Button
          type="button"
          onClick={submit}
          disabled={pending || selectedCount === 0 || selectedCount > MAX_IMPORT_CASES || issues.size > 0}
        >
          {pending ? "가져오는 중…" : `${selectedCount}건 가져오기`}
        </Button>
      </DialogFooter>
    </div>
  );
}

/**
 * Server Action 호출. 본문 한도(6MB) 초과처럼 요청 자체가 거부되면 오류 화면 대신 창 안에 문구 표시
 * (문자 수 한도 안이어도 멀티바이트 문자가 많으면 바이트 수가 한도를 넘을 수 있음)
 */
async function send<T extends { error?: string } | undefined>(call: () => Promise<T>): Promise<T> {
  try {
    return await call();
  } catch {
    // 두 Server Action의 결과 타입 모두 error 문구 하나만으로 실패를 표현
    return { error: "요청을 보내지 못했습니다. 문서 크기와 연결 상태를 확인해 주세요" } as T;
  }
}

function Warnings({ items }: { items: string[] }) {
  if (items.length === 0) {
    return null;
  }
  return (
    <ul className="mt-1 space-y-0.5 text-xs text-amber-800 dark:text-amber-400">
      {items.map((item, index) => (
        <li key={index}>{item}</li>
      ))}
    </ul>
  );
}
