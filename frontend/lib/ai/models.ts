/********************************************************
 파일명 : models.ts
 설 명 : 모델 선택 드롭다운(model-selector.tsx)에 노출할 모델 목록. 실제 라우팅은 LiteLLM 프록시가
 id/apiIdentifier로 처리하며, 이 목록은 CLIENT UI 표시용 카탈로그다.
 *********************************************************/

export interface Model {
  id: string;
  label: string;
  apiIdentifier: string;
  description: string;
}

export const models: Array<Model> = [
  {
    id: 'default',
    label: '기본 모델',
    apiIdentifier: 'default',
    description: 'LiteLLM 게이트웨이에 연결된 모델',
  },
  {
    id: 'gpt-4o-mini',
    label: 'GPT 4o mini',
    apiIdentifier: 'gpt-4o-mini',
    description: 'Small model for fast, lightweight tasks',
  },
  {
    id: 'gpt-4o',
    label: 'GPT 4o',
    apiIdentifier: 'gpt-4o',
    description: 'For complex, multi-step tasks',
  },
] as const;

export const DEFAULT_MODEL_NAME: string = 'default';
