package com.onggijonggi.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Class Name : BffApplication.java
 * Description : bff(Spring Boot WebFlux BFF) 애플리케이션 진입점. 03·CORE(ChatController) 및
 *               추후 02·EDGE(Spring Security/Keycloak)가 이 애플리케이션 위에 함께 구성된다.
 */
@SpringBootApplication
public class BffApplication {

	/**
	* main: 스프링 부트 애플리케이션을 구동한다.
	* @param args 커맨드라인 인자(예: --server.port)
	*/
	public static void main(String[] args) {
		SpringApplication.run(BffApplication.class, args);
	}

}
