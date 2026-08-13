/********************************************************
 파일명 : markdown.tsx
 설 명 : LLM 출력을 마크다운으로 렌더하므로 XSS 표면이 있다. 불변식:
 1) rehype-raw를 사용하지 않는다 → raw HTML이 파싱/실행되지 않는다. <ReactMarkdown skipHtml>로 HTML 노드 자체를 드롭한다.
 2) safeUrlTransform으로 위험한 URL 스킴을 차단한다(링크·이미지 공통).
 *********************************************************/

import { Component, type ReactNode, memo } from 'react';
import ReactMarkdown, { type Components, type Options } from 'react-markdown';
import rehypeKatex from 'rehype-katex';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import { CodeBlock } from './code-block';
import 'katex/dist/katex.min.css';

/** 허용 URL 스킴. 그 외(javascript:, data:, vbscript: ...)는 차단한다. */
const SAFE_URL_SCHEMES = new Set(['http:', 'https:', 'mailto:', 'tel:']);

/** 위험한 URL 스킴을 차단한다. 스킴 없는 상대경로/앵커는 허용, 차단 시 빈 문자열을 반환한다.
 * react-markdown의 urlTransform으로 사용한다. */
function safeUrlTransform(url: string): string {
  // 브라우저가 URL 내 제어문자를 제거해 스킴 사이에 탭을 끼우는 우회를 허용하므로,
  // 스킴 판별 전에 제어문자(C0·DEL·C1 범위)를 제거한다.
  let cleaned = '';
  for (const ch of url) {
    const code = ch.charCodeAt(0);
    const isControl = code <= 0x1f || (code >= 0x7f && code <= 0x9f);
    if (!isControl) {
      cleaned += ch;
    }
  }
  cleaned = cleaned.trim();

  const schemeMatch = /^([a-z][a-z0-9+.-]*):/i.exec(cleaned);
  if (!schemeMatch) {
    return cleaned;
  }
  const scheme = `${schemeMatch[1].toLowerCase()}:`;
  return SAFE_URL_SCHEMES.has(scheme) ? cleaned : '';
}

const components: Components = {
  code: ({ node, className, children, ...props }) => {
    // 펜스드 코드블록만 remark가 language-xxx 클래스를 붙이므로, className 유무로 inline을 판별한다.
    const isInline = !/language-(\w+)/.test(className ?? '');
    return (
      <CodeBlock {...props} className={className} inline={isInline}>
        {children}
      </CodeBlock>
    );
  },
  pre: ({ children }) => <>{children}</>,
  ol: ({ node, children, ...props }) => {
    return (
      <ol className="list-decimal list-outside ml-4" {...props}>
        {children}
      </ol>
    );
  },
  li: ({ node, children, ...props }) => {
    return (
      <li className="py-1" {...props}>
        {children}
      </li>
    );
  },
  // remark-gfm이 체크리스트를 <input type="checkbox" disabled>로 렌더링하는데 접근 가능한
  // 이름이 없으면 접근성 검사에 걸린다.
  input: ({ node, checked, ...props }) => {
    return (
      <input
        {...props}
        checked={checked}
        aria-label={checked ? '완료된 항목' : '미완료 항목'}
      />
    );
  },
  ul: ({ node, children, ...props }) => {
    return (
      <ul className="list-decimal list-outside ml-4" {...props}>
        {children}
      </ul>
    );
  },
  strong: ({ node, children, ...props }) => {
    return (
      <span className="font-semibold" {...props}>
        {children}
      </span>
    );
  },
  a: ({ node, children, href, ...props }) => {
    // href는 urlTransform을 이미 거친 값(차단 시 '').
    return (
      <a
        className="text-blue-500 hover:underline"
        target="_blank"
        rel="noopener noreferrer nofollow"
        href={href || undefined}
        {...props}
      >
        {children}
      </a>
    );
  },
  h1: ({ node, children, ...props }) => {
    return (
      <h1 className="text-3xl font-semibold mt-6 mb-2" {...props}>
        {children}
      </h1>
    );
  },
  h2: ({ node, children, ...props }) => {
    return (
      <h2 className="text-2xl font-semibold mt-6 mb-2" {...props}>
        {children}
      </h2>
    );
  },
  h3: ({ node, children, ...props }) => {
    return (
      <h3 className="text-xl font-semibold mt-6 mb-2" {...props}>
        {children}
      </h3>
    );
  },
  h4: ({ node, children, ...props }) => {
    return (
      <h4 className="text-lg font-semibold mt-6 mb-2" {...props}>
        {children}
      </h4>
    );
  },
  h5: ({ node, children, ...props }) => {
    return (
      <h5 className="text-base font-semibold mt-6 mb-2" {...props}>
        {children}
      </h5>
    );
  },
  h6: ({ node, children, ...props }) => {
    return (
      <h6 className="text-sm font-semibold mt-6 mb-2" {...props}>
        {children}
      </h6>
    );
  },
};

const remarkPlugins = [remarkGfm, remarkMath];
// trust: false → \href 등으로 임의 URL·스크립트 삽입 차단.
// throwOnError: false → $...$ 짝이 통화 표기와 잘못 묶여도 그 부분만 에러로 표시하고 나머지는 계속 렌더링한다.
const rehypePlugins: Options['rehypePlugins'] = [
  [rehypeKatex, { throwOnError: false, trust: false }],
];

/** throwOnError: false로도 못 거르는 KaTeX 렌더링 에러의 이중 안전망. 수식이 깨져도 메시지 전체가
 * 죽지 않도록 마크다운 원문을 그대로 보여주는 선으로 낮춰 대응한다. */
class MathErrorBoundary extends Component<
  { children: ReactNode; fallback: string },
  { hasError: boolean }
> {
  state = { hasError: false };

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  render() {
    if (this.state.hasError) {
      return this.props.fallback;
    }
    return this.props.children;
  }
}

/** ReactMarkdown을 remarkGfm·remarkMath+rehypeKatex·안전 렌더러·skipHtml·safeUrlTransform과 함께 조립한다. */
const NonMemoizedMarkdown = ({ children }: { children: string }) => {
  return (
    <MathErrorBoundary fallback={children}>
      <ReactMarkdown
        remarkPlugins={remarkPlugins}
        rehypePlugins={rehypePlugins}
        components={components}
        skipHtml
        urlTransform={safeUrlTransform}
      >
        {children}
      </ReactMarkdown>
    </MathErrorBoundary>
  );
};

export const Markdown = memo(
  NonMemoizedMarkdown,
  (prevProps, nextProps) => prevProps.children === nextProps.children,
);
