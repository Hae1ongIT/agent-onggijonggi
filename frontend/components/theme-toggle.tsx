'use client';

/********************************************************
 파일명 : theme-toggle.tsx
 설 명 : 라이트/다크 수동 전환 버튼. 시스템 설정과 무관하게 사용자가 직접 고를 수 있게 한다.
 *********************************************************/

import { useTheme } from 'next-themes';
import { useEffect, useState } from 'react';

import { MoonIcon, SunIcon } from './icons';
import { Button } from './ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from './ui/tooltip';

export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();

  // next-themes는 서버·클라이언트 첫 렌더가 같아야 해서 마운트 전엔 실제 테마를 알 수
  // 없다 — 마운트 후에만 아이콘을 확정해 하이드레이션 불일치를 막는다.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  if (!mounted) {
    return <div className="size-10" aria-hidden="true" />;
  }

  const isDark = resolvedTheme === 'dark';

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant="outline"
          className="md:px-2 md:h-fit"
          aria-label={isDark ? '라이트 모드로 전환' : '다크 모드로 전환'}
          onClick={() => setTheme(isDark ? 'light' : 'dark')}
        >
          {isDark ? <SunIcon size={16} /> : <MoonIcon size={16} />}
        </Button>
      </TooltipTrigger>
      <TooltipContent align="end">
        {isDark ? '라이트 모드로 전환' : '다크 모드로 전환'}
      </TooltipContent>
    </Tooltip>
  );
}
