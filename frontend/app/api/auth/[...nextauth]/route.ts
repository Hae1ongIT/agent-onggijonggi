/********************************************************
 파일명 : route.ts (app/api/auth/[...nextauth])
 설 명 : next-auth 카탈로그 라우트. 모든 /api/auth/* 요청을 auth.ts에서 만든 핸들러로 위임한다.
 *********************************************************/

import { handlers } from '@/auth';

export const { GET, POST } = handlers;
