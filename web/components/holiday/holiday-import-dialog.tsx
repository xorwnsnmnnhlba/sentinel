"use client";

import { ClipboardPaste } from "lucide-react";
import { useActionState, useState } from "react";

import { FormError, submitKeepingValues } from "@/components/catalog/form-dialog";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import type { ImportState } from "@/lib/actions/holidays";
import { parseHolidayLines } from "@/lib/holiday";

type Action = (state: ImportState, form: FormData) => Promise<ImportState>;

const PLACEHOLDER = "2026-12-25 성탄절\n2027-01-01 신정\n2027-02-06 설날 연휴";

/**
 * 휴일 여러 건 붙여넣기 (관리자). 입력하는 동안 읽은 건수나 첫 잘못된 줄을 바로 표시, 등록 후 결과(등록·건너뜀)를 보여 줌
 * 닫았다 다시 열면 빈 입력칸
 */
export function HolidayImportDialog({ action }: { action: Action }) {
  const [open, setOpen] = useState(false);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline">
          <ClipboardPaste /> 여러 건 붙여넣기
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>휴일 여러 건 붙여넣기</DialogTitle>
          <DialogDescription>
            한 줄에 날짜와 이름 하나. 이미 등록한 날짜는 건너뛰고, 잘못된 줄이 있으면 아무것도 등록하지 않습니다.
          </DialogDescription>
        </DialogHeader>
        {open && <ImportForm action={action} />}
      </DialogContent>
    </Dialog>
  );
}

function ImportForm({ action }: { action: Action }) {
  const [state, formAction, pending] = useActionState(action, undefined);
  const [lines, setLines] = useState("");

  if (state?.created) {
    return <ImportResult created={state.created.length} skipped={state.skipped ?? []} />;
  }
  const parsed = lines.trim() === "" ? undefined : parseHolidayLines(lines);
  return (
    <form method="post" onSubmit={(event) => submitKeepingValues(event, formAction)} className="space-y-4">
      <div className="space-y-2">
        <Label htmlFor="holiday-lines">휴일 목록 (KST)</Label>
        <Textarea
          id="holiday-lines"
          name="lines"
          rows={10}
          required
          value={lines}
          onChange={(event) => setLines(event.target.value)}
          placeholder={PLACEHOLDER}
          aria-describedby="holiday-lines-status"
          className="font-mono text-xs"
        />
        <p
          id="holiday-lines-status"
          aria-live="polite"
          className={parsed && "error" in parsed ? "text-xs text-destructive" : "text-xs text-muted-foreground"}
        >
          {parsed === undefined
            ? "날짜 형식: 2026-12-25, 2026.12.25, 2026/12/25. 날짜 뒤 요일 (금)은 무시"
            : "error" in parsed
              ? parsed.error
              : `${parsed.holidays.length}건`}
        </p>
      </div>
      <FormError message={state?.error} />
      <DialogFooter>
        <DialogClose asChild>
          <Button type="button" variant="outline" disabled={pending}>
            취소
          </Button>
        </DialogClose>
        <Button type="submit" disabled={pending}>
          {pending ? "등록 중…" : "등록"}
        </Button>
      </DialogFooter>
    </form>
  );
}

function ImportResult({
  created,
  skipped,
}: {
  created: number;
  skipped: { id: number; date: string; name: string }[];
}) {
  return (
    <div className="space-y-4">
      <p role="status" className="text-sm">
        <span className="font-mono">{created}</span>건 등록
        {skipped.length > 0 && (
          <>
            , 이미 등록되어 <span className="font-mono">{skipped.length}</span>건 건너뜀
          </>
        )}
      </p>
      {skipped.length > 0 && (
        <ul aria-label="건너뛴 휴일" className="max-h-48 divide-y overflow-y-auto rounded-md border text-sm">
          {skipped.map((holiday) => (
            <li key={holiday.id} className="flex gap-3 px-3 py-1.5">
              <span className="font-mono text-xs text-muted-foreground">{holiday.date}</span>
              {holiday.name}
            </li>
          ))}
        </ul>
      )}
      <DialogFooter>
        <DialogClose asChild>
          <Button type="button">닫기</Button>
        </DialogClose>
      </DialogFooter>
    </div>
  );
}
