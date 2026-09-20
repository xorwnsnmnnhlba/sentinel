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

export function EnvironmentFields({ environment }: { environment?: EnvironmentItem }) {
  return (
    <>
      <TextField name="name" label="이름" defaultValue={environment?.name} required maxLength={50} placeholder="dev" />
      <TextField
        name="baseUrl"
        label="기본 URL"
        type="url"
        defaultValue={environment?.baseUrl}
        required
        maxLength={500}
        placeholder="https://gw.dev.internal"
        hint="http(s) 절대 URL. 케이스 경로가 뒤에 붙음, 끝의 / 는 제거하여 저장"
      />
      <VariablesEditor defaultValue={environment?.variables} />
      <SecretsEditor savedNames={environment?.secretNames} />
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
