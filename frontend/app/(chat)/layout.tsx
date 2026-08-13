/********************************************************
 파일명 : layout.tsx (app/(chat))
 설 명 : 채팅 라우트 그룹의 공통 레이아웃. 사이드바와 본문을 SidebarProvider로 조립한다.
 쿠키에 저장된 사이드바 접힘 상태를 서버에서 먼저 읽어 첫 렌더부터 반영한다(깜빡임 방지).
 *********************************************************/

import { cookies } from 'next/headers';

import { AppSidebar } from '@/components/app-sidebar';
import { AuthButton } from '@/components/auth-button';
import { ReauthBanner } from '@/components/reauth-banner';
import { SidebarInset, SidebarProvider } from '@/components/ui/sidebar';
import { fetchSessionsForServer } from '@/lib/api/server-history';

export const experimental_ppr = true;

/** sidebar:state 쿠키로 초기 접힘 여부를 결정하고, 로그인 사용자의 세션 목록을 서버에서 미리 조회한다. */
export default async function Layout({
  children,
}: {
  children: React.ReactNode;
}) {
  const cookieStore = await cookies();
  const isCollapsed = cookieStore.get('sidebar:state')?.value !== 'true';
  const serverSessions = await fetchSessionsForServer();

  return (
    <SidebarProvider defaultOpen={!isCollapsed}>
      <AppSidebar
        authSlot={<AuthButton />}
        serverSessions={serverSessions ?? []}
      />
      <SidebarInset>
        {serverSessions === null && (
          <div className="p-2">
            <ReauthBanner />
          </div>
        )}
        {children}
      </SidebarInset>
    </SidebarProvider>
  );
}
