'use server';

/********************************************************
 파일명 : actions.ts
 설 명 : 선택한 모델 id를 쿠키에 저장하는 서버 액션. page.tsx들이 이 쿠키를 읽어 재방문 시에도
 마지막 선택 모델을 복원한다.
 *********************************************************/

import { cookies } from 'next/headers';

/** 사용자가 고른 모델 id를 model-id 쿠키에 저장한다. */
export async function saveModelId(model: string) {
  const cookieStore = await cookies();
  cookieStore.set('model-id', model);
}
