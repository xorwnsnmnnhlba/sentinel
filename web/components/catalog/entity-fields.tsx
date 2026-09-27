import { TextField } from "@/components/catalog/field";
import { SecretsEditor } from "@/components/catalog/secrets-editor";
import { VariablesEditor } from "@/components/catalog/variables-editor";
import type { EnvironmentItem, ProjectItem, SuiteItem } from "@/lib/api/types";

/** 등록·수정 폼 입력 항목. 길이 제한은 Spring 엔티티 컬럼 길이와 일치 */

export function ProjectFields({ project }: { project?: ProjectItem }) {
  return (
    <>
      <TextField name="name" label="이름" defaultValue={project?.name} required maxLength={100} placeholder="billing" />
      <TextField name="description" label="설명" defaultValue={project?.description} maxLength={500} multiline />
    </>
  );
}

/**
 * 환경 등록·수정 입력 항목
 * @param environment 수정할 환경
 * @param copy 복제 등록. 원본의 기본 URL·변수를 채우고 이름은 복제본 이름, 시크릿은 값을 옮길 수 없어 이름만 채워 값 입력 대기
 */
export function EnvironmentFields({
  environment,
  copy,
}: {
  environment?: EnvironmentItem;
  copy?: { from: EnvironmentItem; name: string };
}) {
  const source = environment ?? copy?.from;
  return (
    <>
      <TextField
        name="name"
        label="이름"
        defaultValue={copy ? copy.name : environment?.name}
        required
        maxLength={50}
        placeholder="dev"
      />
      <TextField
        name="baseUrl"
        label="기본 URL"
        type="url"
        defaultValue={source?.baseUrl}
        required
        maxLength={500}
        placeholder="https://gw.dev.internal"
        hint="http(s) 절대 URL. 케이스 경로가 뒤에 붙음, 끝의 / 는 제거하여 저장"
      />
      <VariablesEditor defaultValue={source?.variables} />
      {copy && copy.from.secretNames.length > 0 && (
        <p className="rounded-md bg-amber-500/10 px-3 py-2 text-xs text-amber-800 dark:text-amber-300">
          시크릿은 값을 옮길 수 없어 이름만 채웠습니다. 값을 다시 입력하거나 필요 없는 행은 지워 주세요
        </p>
      )}
      <SecretsEditor savedNames={environment?.secretNames} pendingNames={copy?.from.secretNames} />
    </>
  );
}

export function SuiteFields({ suite }: { suite?: SuiteItem }) {
  return (
    <>
      <TextField name="name" label="이름" defaultValue={suite?.name} required maxLength={100} placeholder="smoke" />
      <TextField name="description" label="설명" defaultValue={suite?.description} maxLength={500} multiline />
    </>
  );
}
