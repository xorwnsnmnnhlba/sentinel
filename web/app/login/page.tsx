import { LoginForm } from "@/components/auth/login-form";

export const metadata = { title: "로그인 · Sentinel" };

export default async function LoginPage(props: PageProps<"/login">) {
  const { from } = await props.searchParams;

  return (
    <div className="mx-auto max-w-sm space-y-6 py-12">
      <div className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">로그인</h1>
        <p className="text-sm text-muted-foreground">계정은 관리자가 초대로 만들어 줍니다</p>
      </div>
      <LoginForm from={typeof from === "string" ? from : undefined} />
    </div>
  );
}
