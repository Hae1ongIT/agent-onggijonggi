// RP-initiated logout에 필요한 id_token을 세션/JWT 타입에 추가한다.
// export {}가 있어야 아래 declare module이 재선언이 아닌 보강으로 병합된다.
export {};

declare module 'next-auth' {
  interface Session {
    idToken?: string;
    accessToken?: string;
    /** 서버측 리프레시 실패 신호. authFetch가 보고 즉시 재로그인시킨다. refreshToken은 여기 없다. */
    error?: 'RefreshAccessTokenError';
  }
}

declare module 'next-auth/jwt' {
  interface JWT {
    idToken?: string;
    accessToken?: string;
    /** JWT(httpOnly 쿠키)에만 존재한다. 브라우저 JS 노출 방지를 위해 Session엔 추가하지 않는다. */
    refreshToken?: string;
    accessTokenExpires?: number;
    error?: 'RefreshAccessTokenError';
  }
}
