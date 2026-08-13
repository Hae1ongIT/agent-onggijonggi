# frontend — Next.js 채팅 UI

Keycloak 로그인이 붙은 채팅 화면. 브라우저는 BFF(`backend/common/bff-web/`)만 호출하고, 게이트웨이·모델 자격증명은 노출되지 않는다.

- [Next.js](https://nextjs.org) App Router — React Server Components·Server Actions
- [AI SDK](https://sdk.vercel.ai/docs) `useChat` — `streamProtocol: 'text'`로 BFF의 프레이밍 없는 텍스트 스트림을 소비한다
- [shadcn/ui](https://ui.shadcn.com) — [Tailwind CSS](https://tailwindcss.com) 스타일링, [Radix UI](https://radix-ui.com) 컴포넌트 프리미티브

어떤 모델을 쓸지는 이 계층이 정하지 않는다. BFF 뒤의 LiteLLM 게이트웨이 설정(`infra/config/litellm_config.yaml`)이 결정한다.

> Vercel [`ai-chatbot`](https://github.com/vercel/ai-chatbot)(Apache-2.0) 템플릿에서 출발했다.
> 변경 내역·라이선스는 루트 [README](../README.md#라이선스)와 [`LICENSE`](LICENSE) 참고.

---

## 실행

이 화면만 따로 띄울 수는 없다. 전 라우트가 Keycloak OIDC 로그인으로 보호돼 있고
(`middleware.ts` — `/api/*`·정적 자산 제외 전부), 대화 이력·스트리밍은 BFF가 있어야 한다.
Keycloak·BFF·PostgreSQL·LiteLLM을 함께 띄우는 절차는 루트 [INSTALL.md](../INSTALL.md)에 있다.

## 동작 메모

- 사이드바 하단 "로그아웃"을 누르면 이 앱의 세션뿐 아니라 Keycloak SSO 세션까지 함께 끊긴다(다시 "로그인"을 눌러도 자동으로 재로그인되지 않고 로그인 폼이 다시 뜬다).
- BFF(`backend/common/bff-web/`)는 이 토큰의 서명·issuer·audience를 검증하고 `USER` 역할을 요구한다. 토큰 없이 호출하면 401, 역할이 없으면 403이 온다.
