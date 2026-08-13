/********************************************************
 파일명 : page.tsx (app/(chat))
 설 명 : "새 대화" 진입점. 매 진입마다 새 세션 id를 생성해 Chat을 렌더링한다(기존 세션을 여는
 chat/[id]/page.tsx와 역할이 다르다).
 *********************************************************/

import { cookies } from 'next/headers';

import { Chat } from '@/components/chat';
import { DEFAULT_MODEL_NAME, models } from '@/lib/ai/models';
import { generateUUID } from '@/lib/utils';

/** 새 세션 id를 생성하고, 쿠키에 저장된 마지막 선택 모델을 복원해 Chat을 렌더링한다. */
export default async function Page() {
  const id = generateUUID();

  const cookieStore = await cookies();
  const modelIdFromCookie = cookieStore.get('model-id')?.value;

  const selectedModelId =
    models.find((model) => model.id === modelIdFromCookie)?.id ||
    DEFAULT_MODEL_NAME;

  return (
    <>
      <Chat
        key={id}
        id={id}
        selectedModelId={selectedModelId}
        serverMessages={[]}
      />
    </>
  );
}
