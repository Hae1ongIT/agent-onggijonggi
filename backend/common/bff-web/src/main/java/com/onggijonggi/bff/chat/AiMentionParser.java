package com.onggijonggi.bff.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class Name : AiMentionParser.java
 * Description : 협업 채팅의 서버 측 {@code @AI} 멘션 계약을 해석한다.
 */
final class AiMentionParser {

	private static final Pattern AI_MENTION = Pattern.compile(
			"(?iu)(?<![\\p{L}\\p{N}_])@ai(?![A-Za-z0-9_])");

	private AiMentionParser() {
	}

	static MentionResult parse(String content) {
		Matcher matcher = AI_MENTION.matcher(content);
		if (!matcher.find()) {
			return new MentionResult(false, content);
		}
		return new MentionResult(true, matcher.replaceAll("").trim());
	}

	record MentionResult(boolean mentioned, String prompt) {
	}

}
