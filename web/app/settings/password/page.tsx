import { ChangePasswordForm } from "@/components/auth/change-password-form";
import { PageHeader } from "@/components/page-header";

export const metadata = { title: "비밀번호 변경 · Sentinel" };

export default function ChangePasswordPage() {
  return (
    <div className="space-y-6">
      <PageHeader title="비밀번호 변경" />
      <ChangePasswordForm />
    </div>
  );
}
