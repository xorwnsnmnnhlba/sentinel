import Link from "next/link";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from "@/components/ui/native-select";
import type { EnvironmentItem, ProjectItem, RunStatus, SuiteItem } from "@/lib/api/types";

export type RunFilterValues = {
  projectId?: string;
  suiteId?: string;
  environmentId?: string;
  status?: string;
  from?: string;
  to?: string;
  /** "true"면 판정 실패·오류가 있는 실행만 */
  failing?: string;
};

const statusOptions: { value: RunStatus; label: string }[] = [
  { value: "COMPLETED", label: "완료" },
  { value: "RUNNING", label: "실행 중" },
  { value: "ABORTED", label: "중단" },
];

/**
 * 실행 이력 필터. GET 폼이라 조건이 주소에 남아 새로고침·공유해도 그대로 유지됨
 * 기간은 KST 날짜 기준이며 서버가 해석 (시작일 00:00 ~ 종료일 다음 날 00:00)
 */
export function RunFilters({
  projects,
  suites,
  environments,
  values,
}: {
  projects: ProjectItem[];
  suites: SuiteItem[];
  environments: EnvironmentItem[];
  values: RunFilterValues;
}) {
  const hasFilter = Object.values(values).some((value) => value);

  return (
    <form
      method="get"
      action="/runs"
      aria-label="실행 이력 필터"
      className="flex flex-wrap items-end gap-3 rounded-lg border p-4"
    >
      <Field label="프로젝트" htmlFor="filter-project">
        <NativeSelect id="filter-project" name="projectId" defaultValue={values.projectId ?? ""} className="w-40">
          <NativeSelectOption value="">전체</NativeSelectOption>
          {projects.map((project) => (
            <NativeSelectOption key={project.id} value={project.id}>
              {project.name}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </Field>

      <Field label="스위트" htmlFor="filter-suite">
        <NativeSelect id="filter-suite" name="suiteId" defaultValue={values.suiteId ?? ""} className="w-40">
          <NativeSelectOption value="">전체</NativeSelectOption>
          {projects.map((project) => (
            <NativeSelectOptGroup key={project.id} label={project.name}>
              {suites
                .filter((suite) => suite.projectId === project.id)
                .map((suite) => (
                  <NativeSelectOption key={suite.id} value={suite.id}>
                    {suite.name}
                  </NativeSelectOption>
                ))}
            </NativeSelectOptGroup>
          ))}
        </NativeSelect>
      </Field>

      <Field label="환경" htmlFor="filter-environment">
        <NativeSelect
          id="filter-environment"
          name="environmentId"
          defaultValue={values.environmentId ?? ""}
          className="w-40"
        >
          <NativeSelectOption value="">전체</NativeSelectOption>
          {projects.map((project) => (
            <NativeSelectOptGroup key={project.id} label={project.name}>
              {environments
                .filter((environment) => environment.projectId === project.id)
                .map((environment) => (
                  <NativeSelectOption key={environment.id} value={environment.id}>
                    {environment.name}
                  </NativeSelectOption>
                ))}
            </NativeSelectOptGroup>
          ))}
        </NativeSelect>
      </Field>

      <Field label="상태" htmlFor="filter-status">
        <NativeSelect id="filter-status" name="status" defaultValue={values.status ?? ""} className="w-32">
          <NativeSelectOption value="">전체</NativeSelectOption>
          {statusOptions.map((status) => (
            <NativeSelectOption key={status.value} value={status.value}>
              {status.label}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </Field>

      <Field label="시작일 (KST)" htmlFor="filter-from">
        <Input id="filter-from" name="from" type="date" defaultValue={values.from ?? ""} className="w-40" />
      </Field>

      <Field label="종료일 (KST)" htmlFor="filter-to">
        <Input id="filter-to" name="to" type="date" defaultValue={values.to ?? ""} className="w-40" />
      </Field>

      <label className="flex h-8 items-center gap-2 text-sm">
        <input
          type="checkbox"
          name="failing"
          value="true"
          defaultChecked={values.failing === "true"}
          className="size-4 accent-primary"
        />
        실패·오류만
      </label>

      <div className="flex gap-2">
        <Button type="submit">조회</Button>
        {hasFilter && (
          <Button asChild variant="outline">
            <Link href="/runs">초기화</Link>
          </Button>
        )}
      </div>
    </form>
  );
}

function Field({ label, htmlFor, children }: { label: string; htmlFor: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={htmlFor} className="text-xs text-muted-foreground">
        {label}
      </Label>
      {children}
    </div>
  );
}
