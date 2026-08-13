'use client';

/********************************************************
 파일명 : code-block.tsx
 설 명 : markdown.tsx가 렌더링하는 코드 조각을 인라인과 블록으로 구분해 스타일링한다.
 블록 코드는 markdown.tsx의 pre 오버라이드가 <>로 감싸 이중 래핑을 피한다.
 *********************************************************/

import type { HTMLAttributes, ReactNode } from 'react';
import type { ExtraProps } from 'react-markdown';

import { cn } from '@/lib/utils';

type CodeBlockProps<T extends boolean> = {
  children: ReactNode;
  className?: string;
  inline?: T;
} & ExtraProps &
  (T extends true
    ? HTMLAttributes<HTMLElement>
    : HTMLAttributes<HTMLPreElement>);

/** inline이 true면 배경색만 있는 짧은 <code>로, false면 스크롤 가능한 <pre><code> 블록으로 렌더링한다. */
export function CodeBlock<T extends boolean>({
  node,
  inline,
  className,
  children,
  ...props
}: CodeBlockProps<T>) {
  if (inline) {
    return (
      <code
        className={cn(
          'text-sm bg-zinc-100 dark:bg-zinc-800 py-0.5 px-1 rounded-md',
          className,
        )}
        {...props}
      >
        {children}
      </code>
    );
  } else {
    return (
      <div className="not-prose flex flex-col">
        <pre
          {...props}
          className={`text-sm w-full overflow-x-auto dark:bg-zinc-900 p-4 border border-zinc-200 dark:border-zinc-700 rounded-xl dark:text-zinc-50 text-zinc-900`}
        >
          <code className="whitespace-pre-wrap break-words">{children}</code>
        </pre>
      </div>
    );
  }
}
