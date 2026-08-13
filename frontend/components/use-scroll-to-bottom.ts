/********************************************************
 파일명 : use-scroll-to-bottom.ts
 설 명 : 메시지 목록 컨테이너의 DOM 변화를 MutationObserver로 감시해, 사용자가 바닥 근처에
 있을 때만 맨 아래로 자동 스크롤하는 훅.
 *********************************************************/

import { useEffect, useRef, type RefObject } from 'react';

// instant 스크롤의 서브픽셀 오차를 흡수하는 여유값 — 0px면 바닥에 있어도 오차로 "이탈"로
// 오판해 자동 스크롤이 간헐적으로 멈출 수 있다.
const BOTTOM_THRESHOLD_PX = 50;

/** 사용자가 바닥에서 BOTTOM_THRESHOLD_PX 이내에 있을 때만 endRef로 즉시 스크롤한다.
 * 위로 스크롤해 이탈한 상태라면 자동 스크롤하지 않는다. */
export function useScrollToBottom<T extends HTMLElement>(): [
  RefObject<T>,
  RefObject<T>,
] {
  const containerRef = useRef<T>(null);
  const endRef = useRef<T>(null);
  const isAtBottomRef = useRef(true);

  useEffect(() => {
    const container = containerRef.current;
    const end = endRef.current;

    if (container && end) {
      const handleScroll = () => {
        const distanceFromBottom =
          container.scrollHeight -
          container.scrollTop -
          container.clientHeight;
        isAtBottomRef.current = distanceFromBottom <= BOTTOM_THRESHOLD_PX;
      };

      const observer = new MutationObserver(() => {
        if (isAtBottomRef.current) {
          end.scrollIntoView({ behavior: 'instant', block: 'end' });
        }
      });

      container.addEventListener('scroll', handleScroll);
      observer.observe(container, {
        childList: true,
        subtree: true,
        attributes: true,
        characterData: true,
      });

      return () => {
        container.removeEventListener('scroll', handleScroll);
        observer.disconnect();
      };
    }
  }, []);

  return [containerRef, endRef];
}
