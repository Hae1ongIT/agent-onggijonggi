# 기여 안내

앞쪽은 **개발 환경 구성**, 뒤쪽은 [**기여 절차**](#이슈-먼저)다. 앱을 쓰기만 할 거라면
[INSTALL.md](INSTALL.md)로 도커 스택을 띄우는 편이 빠르다.

---

# 개발 환경 구성

코드를 고치면서 개발할 때의 구성이다.

프론트와 BFF는 호스트에서 직접 실행해 핫리로드를 쓰고, 나머지는 컨테이너로 띄워 붙인다.

```
호스트:     frontend(3000) · bff(8090)
컨테이너:   keycloak(8081) · postgres(5442) · LLM 엔드포인트
```

도커 구성([INSTALL.md](INSTALL.md))과 두 가지가 다르다 — 프론트를 **호스트에서 직접 띄워** 주소가
`http://localhost:3010`이 아닌 `localhost:3000`이고, **LiteLLM을 거치지 않아** BFF가 LLM 엔드포인트에
직접 붙는다. 그래서 쓸 모델도 `litellm_config.yaml`이 아니라 BFF 설정에서 정한다(4단계).

## 준비물

- **JDK 17** — `build.gradle` 툴체인이 17로 고정돼 있다. **`JAVA_HOME`이 17을 가리키는지 확인한다** (`java -version`) — 옛 JDK가 잡혀 있으면 gradle wrapper가 배포본을 내려받는 단계에서 인증서 오류로 먼저 막힌다
- **Bun** — https://bun.sh
- **Docker**
- **OpenAI 호환 LLM 엔드포인트** — [Ollama](https://ollama.com) 또는 상용 API 키

## 클론 후 한 번만

```bash
git config core.hooksPath .githooks
```

마이그레이션 SQL을 커밋할 때 DB 식별자 이름과 설계 불변식을 검사한다.

---

## 1. Keycloak

`infra/config/realm-app.json`을 임포트하면 realm·client·역할·계정이 함께 만들어진다.

**macOS · Linux**

```bash
docker run -d --name dev-keycloak -p 8081:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -e KEYCLOAK_REALM=app-realm \
  -e KEYCLOAK_CLIENT_ID=ogjg-client \
  -e KEYCLOAK_CLIENT_SECRET=devsecret \
  -e PUBLIC_FRONTEND_URL=http://localhost:3000 \
  -e APP_USER=devuser -e APP_USER_PASSWORD=devpass123 \
  -v "$(pwd)/infra/config/realm-app.json:/opt/keycloak/data/import/realm-app.json:ro" \
  quay.io/keycloak/keycloak:26.0 start-dev --import-realm
```

**Windows (PowerShell)**

```powershell
docker run -d --name dev-keycloak -p 8081:8080 `
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin `
  -e KEYCLOAK_REALM=app-realm `
  -e KEYCLOAK_CLIENT_ID=ogjg-client `
  -e KEYCLOAK_CLIENT_SECRET=devsecret `
  -e PUBLIC_FRONTEND_URL=http://localhost:3000 `
  -e APP_USER=devuser -e APP_USER_PASSWORD=devpass123 `
  -v "${PWD}/infra/config/realm-app.json:/opt/keycloak/data/import/realm-app.json:ro" `
  quay.io/keycloak/keycloak:26.0 start-dev --import-realm
```

`http://localhost:8081/realms/app-realm/.well-known/openid-configuration`이 응답하면 준비된 것이다.

`PUBLIC_FRONTEND_URL`이 리다이렉트 주소가 되므로 프론트를 다른 포트로 띄운다면 여기서 맞춘다.
realm은 최초 기동 때만 만들어진다 — 값을 바꾸려면 `docker rm -f dev-keycloak` 후 다시 띄운다.

## 2. PostgreSQL

**macOS · Linux**

```bash
docker run -d --name dev-postgres -p 5442:5432 \
  -e POSTGRES_USER=appuser -e POSTGRES_PASSWORD=devpassword -e POSTGRES_DB=appdb \
  postgres:16-alpine
```

**Windows (PowerShell)**

```powershell
docker run -d --name dev-postgres -p 5442:5432 `
  -e POSTGRES_USER=appuser -e POSTGRES_PASSWORD=devpassword -e POSTGRES_DB=appdb `
  postgres:16-alpine
```

## 3. LLM 엔드포인트

BFF는 OpenAI 호환 API로 말한다. 로컬이라면 Ollama가 간단하다.

```bash
ollama pull gemma3:4b
ollama serve
```

> 💡 Windows·macOS의 Ollama 앱은 설치와 함께 백그라운드로 뜨므로 `ollama serve`가 필요 없다. 리눅스는 설치 스크립트가 systemd 서비스로 등록한다.
> 💡 BFF가 호스트에서 직접 도는 구성이라 `localhost:11434`로 붙는다 — 도커 구성과 달리 Ollama를 외부에 개방(`OLLAMA_HOST=0.0.0.0`)할 필요가 없다.

모델은 각자의 라이선스·이용약관을 따른다 — [README 라이선스](README.md#모델) 참고.

## 4. `application-local.properties`

`backend/common/bff-web/src/main/resources/`에 만든다(gitignore 대상).

```properties
spring.datasource.password=devpassword

spring.ai.openai.base-url=http://localhost:11434/v1
spring.ai.openai.api-key=ollama
spring.ai.openai.chat.options.model=gemma3:4b
```

`application.properties`의 기본값이 `localhost`를 가리키므로 주소는 대부분 그대로 둔다.
`api-key`는 Ollama에선 쓰이지 않지만 비워두면 기동에 실패하니 아무 문자열이나 넣는다.
모델명은 `ollama list`에 나오는 이름을 쓴다.

## 5. BFF

**macOS · Linux**

```bash
cd backend/common/bff-web
./gradlew bootRun
```

**Windows (PowerShell)**

```powershell
cd backend\common\bff-web
.\gradlew.bat bootRun
```

빈 DB라면 **최초 1회**는 스키마를 만들도록 Flyway를 켜서 띄운다 — `bootRun` 뒤에
`--args="--spring.flyway.enabled=true"`를 붙인다. 기본 프로파일에서는 꺼져 있다.

테스트는 `bootRun` 자리에 `test`를 넣는다.

`http://localhost:8090/actuator/health`가 `{"status":"UP"}`이면 정상이다.

## 6. 프론트엔드

`frontend/.env.example`을 `.env.local`로 복사해 채운다.

**macOS · Linux**

```bash
cd frontend
cp .env.example .env.local
openssl rand -base64 32      # NEXTAUTH_SECRET 용
```

**Windows (PowerShell)**

```powershell
cd frontend
Copy-Item .env.example .env.local
# NEXTAUTH_SECRET 용 — openssl이 없으면 아래로 대신한다
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
```

채울 값:

```
NEXTAUTH_URL=http://localhost:3000
NEXTAUTH_SECRET=<위에서 만든 값>
KEYCLOAK_ISSUER=http://localhost:8081/realms/app-realm
KEYCLOAK_CLIENT_ID=ogjg-client
KEYCLOAK_CLIENT_SECRET=devsecret
NEXT_PUBLIC_BFF_BASE_URL=http://localhost:8090
```

```bash
bun install
bun dev
```

`http://localhost:3000` → `devuser` / `devpass123`

lint·format은 `bun lint` · `bun format`.

---

## 끄기

```bash
docker stop dev-keycloak dev-postgres     # 프론트·BFF는 Ctrl+C
```

컨테이너를 지우지 않으면 realm 설정과 데이터가 남아 `docker start`로 재개할 수 있다.

## 막혔을 때

- **로그인 후 첫 화면에서 `fetch failed`** — BFF가 떠 있지 않다.
- **로그인은 되는데 질문에 401** — 토큰의 `aud`에 `ogjg-client`가 없다.
- **질문에 403** — 계정에 `USER` realm role이 없다.
- **로그인 화면이 반복된다** — 프론트와 BFF가 서로 다른 Keycloak을 본다.
- **`Cannot find a Java installation ... languageVersion=17`** — JDK 17이 필요하다.
- **`gradlew` 첫 실행이 `PKIX path building failed`로 죽는다** — `JAVA_HOME`이 옛 JDK(8 등)를 가리켜 gradle 배포본을 내려받지 못하는 것이다. JDK 17을 `JAVA_HOME`으로 지정한다.
- **`ClassNotFoundException: ...GradleWorkerMain`** — 테스트 워커가 Gradle 자신의 클래스를 못 찾는 경우다. 홈 경로에 한글 등 ASCII 밖 문자가 있으면 Windows에서 나타난다. 저장소와 `GRADLE_USER_HOME`을 ASCII 경로로 옮기거나, 컨테이너 안에서 돌린다 — `docker run --rm -v "$PWD:/src:ro" -w /work eclipse-temurin:17-jdk sh -c "cp -r /src /work/p && cd /work/p && ./gradlew test --no-daemon"`

Keycloak 설정을 고쳤다면 로그아웃 후 다시 로그인한다 — 이미 발급된 토큰은 바뀌지 않는다.

---

# 기여 절차

## 이슈 먼저

버그든 기능이든 이슈를 먼저 연다. 브랜치 이름에 그 번호를 쓴다.

**보안 취약점은 예외다** — 공개 이슈로 열지 않는다. [SECURITY.md](SECURITY.md)의 비공개 통로를 쓴다.

## 브랜치

`main`에서 따고 `<이슈번호>-<짧은-설명>`으로 이름 짓는다.

```
42-session-rename
57-token-audience-npe
```

## 커밋

```
타입(scope): 요약
```

타입은 `feat` `fix` `docs` `chore` `refactor`, scope는 `frontend` `bff` `infra` `docs`
`scripts` 중 하나를 쓰고 여러 곳에 걸치면 생략한다.

```
feat(bff): 세션 이름변경 엔드포인트 추가
fix(frontend): aud 클레임 없는 토큰 NPE 수정
docs: 빠른 시작 누락 단계 보완
```

## PR

`main`으로 보낸다. 승인 1명이면 머지한다.

- [ ] `gradlew build` 통과 (백엔드를 고쳤다면)
- [ ] `bun lint` 통과 (프론트를 고쳤다면)
- [ ] 관련 문서를 함께 고쳤다

## 라이선스

이 저장소에 보낸 기여는 [Apache License 2.0](LICENSE)으로 제공하는 것으로 본다.
