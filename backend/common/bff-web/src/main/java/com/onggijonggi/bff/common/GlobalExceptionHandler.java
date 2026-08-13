package com.onggijonggi.bff.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/**
 * Class Name : GlobalExceptionHandler.java
 * Description : 요청 검증·본문 파싱 실패, 상태 예외(404 등), 그 외 처리되지 않은 예외를 공통 에러
 *               봉투 형식으로 변환한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(WebExchangeBindException.class)
	public ResponseEntity<ErrorResponse> handleValidation(WebExchangeBindException ex, ServerWebExchange exchange) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
				.orElse("요청 형식이 올바르지 않습니다.");
		return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_ERROR", message, traceId(exchange)));
	}

	@ExceptionHandler(ServerWebInputException.class)
	public ResponseEntity<ErrorResponse> handleMalformed(ServerWebInputException ex, ServerWebExchange exchange) {
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("MALFORMED_REQUEST", "요청 본문을 읽을 수 없습니다.", traceId(exchange)));
	}

	/**
	* WebFlux가 라우팅 매칭 실패로 던지는 경우뿐 아니라, ChatController의 세션 소유권 검증처럼
	* 컨트롤러가 직접 던지는 경우도 여기로 온다(메서드 이름이 시사하는 것보다 넓다).
	*/
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(ResponseStatusException ex, ServerWebExchange exchange) {
		HttpStatusCode status = ex.getStatusCode();
		boolean isNotFound = status == HttpStatus.NOT_FOUND;
		String code = isNotFound ? "NOT_FOUND" : "REQUEST_ERROR";
		String message = isNotFound ? "요청한 리소스를 찾을 수 없습니다." : "요청을 처리할 수 없습니다.";
		return ResponseEntity.status(status).body(ErrorResponse.of(code, message, traceId(exchange)));
	}

	/** 예외 상세·스택트레이스는 서버 로그에만 남기고 응답에는 고정 안전 문구만 포함한다. */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, ServerWebExchange exchange) {
		log.error("처리되지 않은 예외 발생(traceId={})", traceId(exchange), ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ErrorResponse.of("INTERNAL_ERROR", "서버 오류가 발생했습니다.", traceId(exchange)));
	}

	private String traceId(ServerWebExchange exchange) {
		return exchange.getAttribute(TraceIdWebFilter.TRACE_ID_ATTR);
	}

}
