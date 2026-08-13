/********************************************************
 파일명 : page.tsx (app/(chat)/chat/[id])
 설 명 : URL의 세션 id로 특정 세션 화면을 여는 페이지. 세션 탭(app-sidebar.tsx) 클릭 시 이 라우트로 이동한다.
 *********************************************************/

import { cookies } from 'next/headers';

import { Chat } from '@/components/chat';
import { DEFAULT_MODEL_NAME, models } from '@/lib/ai/models';
import { fetchSessionMessagesForServer } from '@/lib/api/server-history';

/** 쿠키에 저장된 마지막 선택 모델을 복원하고, URL의 세션 id로 Chat을 렌더링한다. */
export default async function Page({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  const cookieStore = await cookies();
  const modelIdFromCookie = cookieStore.get('model-id')?.value;
  const selectedModelId =
    models.find((model) => model.id === modelIdFromCookie)?.id ||
    DEFAULT_MODEL_NAME;
  const serverMessages = await fetchSessionMessagesForServer(id);

  return (
    <>
      <Chat
        key={id}
        id={id}
        selectedModelId={selectedModelId}
        serverMessages={serverMessages ?? []}
      />
    </>
  );
}
