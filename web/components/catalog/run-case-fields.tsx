import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import type { EnvironmentItem } from "@/lib/api/types";

/**
 * 케이스 단건 실행 대화상자의 환경 선택. 환경이 하나면 그 환경이 선택된 상태
 */
export function RunCaseFields({ environments }: { environments: EnvironmentItem[] }) {
  return (
    <div className="space-y-2">
      <Label htmlFor="run-case-environment">실행 환경</Label>
      <NativeSelect id="run-case-environment" name="environmentId" className="w-full" required>
        {environments.map((environment) => (
          <NativeSelectOption key={environment.id} value={environment.id}>
            {environment.name}
          </NativeSelectOption>
        ))}
      </NativeSelect>
    </div>
  );
}
