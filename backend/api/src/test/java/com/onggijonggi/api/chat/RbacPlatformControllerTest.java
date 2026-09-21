package com.onggijonggi.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.onggijonggi.api.authz.RbacProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Class Name : RbacPlatformControllerTest.java
 * Description : Verifies the phase-1 control plane remains available before RBAC enforcement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({FakeChatModelConfig.class, FakeJwtDecoderConfig.class})
class RbacPlatformControllerTest {

	@LocalServerPort
	private int port;
	@Autowired
	private RbacProperties rbacProperties;
	private RestTestClient client;

	@BeforeEach
	void setUp() {
		client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void platformBootstrapRunsWithDefaultDisabledEnforcement() {
		// DB-TST-064: this first-PR control plane must not wait for tenant request enforcement.
		assertThat(rbacProperties.isEnforce()).isFalse();
		client.post().uri("/api/platform/rbac/bootstrap/retry")
				.header(HttpHeaders.AUTHORIZATION, "Bearer "
						+ TestJwtSupport.signedJwt("platform", java.util.List.of("PLATFORM_ADMIN"),
								java.util.List.of("ogjg-client")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.OK)
				.expectBody()
				.jsonPath("$.processedTenants").isArray()
				.jsonPath("$.processedTenants.length()").isEqualTo(0);
	}

	@Test
	void userRoleCannotUsePlatformBootstrapEvenWhenEnforcementIsDisabled() {
		// DB-TST-064: PLATFORM_ADMIN boundary is independent from the future app.rbac.enforce switch.
		client.post().uri("/api/platform/rbac/bootstrap/retry")
				.header(HttpHeaders.AUTHORIZATION, "Bearer "
						+ TestJwtSupport.signedJwt("user", java.util.List.of("USER"),
								java.util.List.of("ogjg-client")))
				.exchange()
				.expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
	}
}
