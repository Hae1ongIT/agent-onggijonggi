'use client';

/********************************************************
 파일명 : theme-provider.tsx
 설 명 : next-themes ThemeProvider를 재노출하는 얇은 래퍼. app/layout.tsx가 전체 앱을 이걸로
 감싸 attribute="class" 기반 라이트/다크 전환 컨텍스트를 제공한다.
 *********************************************************/

import { ThemeProvider as NextThemesProvider, type ThemeProviderProps } from 'next-themes';

export function ThemeProvider({ children, ...props }: ThemeProviderProps) {
  return <NextThemesProvider {...props}>{children}</NextThemesProvider>;
}
