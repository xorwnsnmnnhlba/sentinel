import { ChangePasswordForm } from "@/components/auth/change-password-form";
import { PageHeader } from "@/components/page-header";

export const metadata = { title: "비밀번호 변경 · Sentinel" };

export default function ChangePasswordPage() {
  return (
    <div className="space-y-6">
      <PageHeader title="비밀번호 변경" description="현재 비밀번호를 확인한 뒤 새 비밀번호로 바꿉니다" />
      <ChangePasswordForm />
    </div>
  );
}
