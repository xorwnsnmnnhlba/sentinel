import { ChangePasswordForm } from "@/components/auth/change-password-form";

export const metadata = { title: "비밀번호 변경 · Sentinel" };

export default function ChangePasswordPage() {
  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">비밀번호 변경</h1>
        <p className="text-sm text-muted-foreground">현재 비밀번호를 확인한 뒤 새 비밀번호로 바꿉니다</p>
      </div>
      <ChangePasswordForm />
    </div>
  );
}
