/********************************************************
 파일명 : use-mobile.tsx (hooks)
 설 명 : 뷰포트가 모바일 폭인지 추적하는 훅. ui/sidebar.tsx가 이 값으로 데스크톱 사이드바와 모바일 드로어를 전환한다.
 *********************************************************/

import { useEffect, useState } from 'react';

const MOBILE_BREAKPOINT = 768;

/** matchMedia로 뷰포트 폭이 MOBILE_BREAKPOINT 미만인지 감시한다. 초기값 undefined를 `!!`로
 * boolean 강제해, SSR에서 항상 false로 시작하게 한다(하이드레이션 불일치 방지). */
export function useIsMobile() {
  const [isMobile, setIsMobile] = useState<boolean | undefined>(undefined);

  useEffect(() => {
    const mql = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`);
    const onChange = () => {
      setIsMobile(window.innerWidth < MOBILE_BREAKPOINT);
    };
    mql.addEventListener('change', onChange);
    setIsMobile(window.innerWidth < MOBILE_BREAKPOINT);
    return () => mql.removeEventListener('change', onChange);
  }, []);

  return !!isMobile;
}
