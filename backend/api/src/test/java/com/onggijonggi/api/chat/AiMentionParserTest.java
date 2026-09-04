package com.onggijonggi.api.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Class Name : AiMentionParserTest.java
 * Description : 서버 측 {@code @AI} 멘션 인식과 프롬프트 정규화를 검증한다.
 */
class AiMentionParserTest {

	@Test
	void removesEveryRecognizedMentionAndTrimsOnlyThePrompt() {
		AiMentionParser.MentionResult result = AiMentionParser.parse("  @AI 첫 요청, @AI야 두 번째 요청  ");

		assertThat(result.mentioned()).isTrue();
		assertThat(result.prompt()).isEqualTo("첫 요청, 야 두 번째 요청");
	}

	@Test
	void recognizesKoreanSuffixButNotIdentifierCharacters() {
		assertThat(AiMentionParser.parse("@AI에게 알려줘").mentioned()).isTrue();
		assertThat(AiMentionParser.parse("team@AI에게 알려줘").mentioned()).isFalse();
		assertThat(AiMentionParser.parse("@AIX 알려줘").mentioned()).isFalse();
		assertThat(AiMentionParser.parse("@AI_1 알려줘").mentioned()).isFalse();
	}

	@Test
	void preservesWhitespaceChunksInTheRemainingPrompt() {
		AiMentionParser.MentionResult result = AiMentionParser.parse("@AI keep   these spaces");

		assertThat(result.prompt()).isEqualTo("keep   these spaces");
	}

}
