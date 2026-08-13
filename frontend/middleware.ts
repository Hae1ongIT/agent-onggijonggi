/********************************************************
 파일명 : middleware.ts
 설 명 : 보호 라우트 게이트. auth.ts의 authorized 콜백이 실제 인가 로직을 담당하며, 이 파일엔
 로직이 없다 — auth.ts가 단일 진실 원천이다.
 *********************************************************/

export { auth as middleware } from '@/auth';

// /api/auth·/api/chat·정적 자산은 matcher에서 제외한다 — 포함하면 로그인 흐름 자체가 막힌다.
export const config = {
  matcher: ['/((?!api|_next/static|_next/image|favicon.ico).*)'],
};
