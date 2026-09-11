/********************************************************
 파일명 : refresh-gate.ts (lib/auth)
 설 명 : next-auth의 jwt 콜백이 accessToken을 언제 선제 리프레시할지 판정한다(이슈 #181).
 auth.ts에서 쓰지만 별도 모듈로 뗀 이유 — auth.ts는 next-auth를 로드하느라 vitest(node)
 환경에서 import되지 않는다. 이 판정만 떼어야 단위 테스트가 경계값을 고정할 수 있다.
 다만 "next-auth가 이 판정을 매 getSession()마다 부르는가"는 여기서 검증되지 않는다 —
 그건 실행 중인 스택으로만 확인된다(docs 협업채팅-181, "A·B 착수 전 검증").
 *********************************************************/

/** accessToken 만료 이 시간 전부터 선제 리프레시한다. 만료 "후"에만 갱신하면 협업채팅 WS가
 * 수명이 몇 초 남은 토큰을 받아 붙자마자 4000으로 끊기고 재연결 루프에 빠진다(이슈 #181).
 * 값은 backend `KeycloakAdminClient.EXPIRY_SAFETY_MARGIN`(30초)과 맞춘다.
 * IMPORTANT: 실서버 Keycloak Access Token Lifespan이 이 값 이하이면 게이트가 늘 참이 돼 매
 * getSession()이 리프레시를 호출한다(동작은 하나 과다 호출) — 그 경우 이 값을 재조정한다.
 * Lifespan은 아직 실측하지 않았다(2026-09-10 결정). 근거·후속은 docs 협업채팅-181 문서. */
export const REFRESH_MARGIN_MS = 30_000;

/** 선제 리프레시가 필요한가. 만료까지 {@link REFRESH_MARGIN_MS} 미만 남았거나, 만료 시각을
 * 아예 모르면(로그인 때 `account.expires_at` 누락 등) `true`. `now`는 테스트 주입용. */
export function needsProactiveRefresh(
  expiresAtMs: number | undefined | null,
  now: number = Date.now(),
): boolean {
  if (!expiresAtMs) return true;
  return now >= expiresAtMs - REFRESH_MARGIN_MS;
}
