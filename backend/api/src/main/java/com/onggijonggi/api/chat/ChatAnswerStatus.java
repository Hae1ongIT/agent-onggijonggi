package com.onggijonggi.api.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Class Name : ChatAnswerStatus.java
 * Description : ChatAnswerFrame의 진행 상태. 프론트 WsFrame(frontend/lib/transport/frames.ts)의
 *               status: 'streaming' | 'done' | 'cancelled' | 'denied' 리터럴 유니온과 JSON 표현이
 *               정확히 같아야 한다. CANCELLED·DENIED는 DIRECT 전용이다(이슈 #162) — COLLAB은
 *               지금처럼 STREAMING·DONE만 쓴다.
 */
public enum ChatAnswerStatus {

	@JsonProperty("streaming") STREAMING,
	@JsonProperty("done") DONE,
	@JsonProperty("cancelled") CANCELLED,
	@JsonProperty("denied") DENIED

}
