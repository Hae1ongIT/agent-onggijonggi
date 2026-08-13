/********************************************************
 파일명 : reauth-banner.tsx (components)
 설 명 : 서버사이드 이력 조회(server-history.ts)가 액세스 토큰 만료로 실패했을 때 보여주는 안내 배너.
 원인을 알리고 재로그인 경로(auth-button.tsx와 동일한 signIn 서버 액션)를 제공한다.
 *********************************************************/

import { signIn } from '@/auth';
import { Button } from '@/components/ui/button';

/** 세션 목록·메시지 조회가 REAUTH_REQUIRED로 실패한 화면 상단에 배치한다. */
export function ReauthBanner() {
  return (
    <div className="flex items-center justify-between gap-3 rounded-md border border-destructive/50 bg-destructive/10 px-4 py-2 text-sm text-destructive">
      <span>로그인이 만료되어 대화 내역을 불러오지 못했습니다.</span>
      <form
        action={async () => {
          'use server';
          await signIn('keycloak');
        }}
      >
        <Button type="submit" variant="outline" size="sm">
          다시 로그인
        </Button>
      </form>
    </div>
  );
}
