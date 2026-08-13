/********************************************************
 파일명 : auth-button.tsx
 설 명 : 로그인/로그아웃 트리거. 서버 컴포넌트로 (chat)/layout.tsx의 사이드바 하단(authSlot)에 배치된다.
 *********************************************************/

import { redirect } from 'next/navigation';

import { auth, signIn, signOut } from '@/auth';
import { Button } from '@/components/ui/button';

/** 로그인 여부에 따라 "로그인" 또는 사용자명+"로그아웃" 버튼을 렌더링한다. 로그아웃은
 * Keycloak end_session_endpoint로도 리다이렉트해 SSO 세션까지 종료한다 — 그러지 않으면
 * 재로그인 클릭 시 로그인 폼 없이 자동 재인증돼버린다(OIDC SSO 특성). */
export async function AuthButton() {
  const session = await auth();

  if (!session?.user) {
    return (
      <form
        action={async () => {
          'use server';
          await signIn('keycloak');
        }}
      >
        <Button type="submit" variant="outline" className="w-full">
          로그인
        </Button>
      </form>
    );
  }

  const idToken = session.idToken;

  return (
    <form
      action={async () => {
        'use server';
        await signOut({ redirect: false });

        const endSessionUrl = new URL(
          `${process.env.KEYCLOAK_ISSUER}/protocol/openid-connect/logout`,
        );
        endSessionUrl.searchParams.set(
          'post_logout_redirect_uri',
          process.env.NEXTAUTH_URL ?? 'http://localhost:3000',
        );
        if (idToken) {
          endSessionUrl.searchParams.set('id_token_hint', idToken);
        }
        redirect(endSessionUrl.toString());
      }}
      className="flex items-center justify-between gap-2"
    >
      <span className="text-sm truncate">
        {session.user.name ?? session.user.email}
      </span>
      <Button type="submit" variant="ghost" size="sm">
        로그아웃
      </Button>
    </form>
  );
}
