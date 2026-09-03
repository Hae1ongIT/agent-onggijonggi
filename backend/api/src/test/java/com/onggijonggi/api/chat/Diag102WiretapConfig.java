package com.onggijonggi.api.chat;

import io.netty.handler.logging.LogLevel;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

/**
 * Class Name : Diag102WiretapConfig.java
 * Description : 이슈 #102 진단용 임시 설정이다. 6차 조사까지 애플리케이션 코드(Spring WebFlux
 *               핸들러) 레벨에서는 로그를 심을 자리가 소진됐다 — 클라이언트가 보낸 프레임이
 *               서버의 session.receive()에 도달하기 전에 사라진다는 것까지만 확인됐다. 그 아래
 *               (Netty 채널 파이프라인)에서 실제로 바이트가 오가는지 직접 보기 위해 서버
 *               HttpServer에 wiretap을 붙인다. 원인이 규명되면 이 클래스와 테스트의 @Import,
 *               freshClient()의 wiretap 호출을 통째로 지운다.
 */
@TestConfiguration
class Diag102WiretapConfig {

	@Bean
	WebServerFactoryCustomizer<NettyReactiveWebServerFactory> diag102ServerWiretap() {
		return factory -> factory.addServerCustomizers(httpServer -> httpServer.wiretap(
				"diag-102-wiretap-server", LogLevel.INFO, AdvancedByteBufFormat.TEXTUAL));
	}

}
