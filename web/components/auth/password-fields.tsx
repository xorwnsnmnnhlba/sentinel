import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/**
 * 새 비밀번호 입력 두 칸. 확인 값이 다르면 저장 전에 화면에서 막음
 */
export function NewPasswordFields({ name = "password", label = "새 비밀번호" }: { name?: string; label?: string }) {
  return (
    <>
      <div className="space-y-2">
        <Label htmlFor={name}>{label}</Label>
        <Input id={name} name={name} type="password" autoComplete="new-password" minLength={10} required />
        <p className="text-xs text-muted-foreground">10자 이상</p>
      </div>
      <div className="space-y-2">
        <Label htmlFor={`${name}Confirmation`}>{label} 확인</Label>
        <Input
          id={`${name}Confirmation`}
          name={`${name}Confirmation`}
          type="password"
          autoComplete="new-password"
          minLength={10}
          required
        />
      </div>
    </>
  );
}
