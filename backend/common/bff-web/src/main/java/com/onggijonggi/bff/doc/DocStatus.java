package com.onggijonggi.bff.doc;

/**
 * Class Name : DocStatus.java
 * Description : doc.status(V5__doc_lifecycle_and_version.sql) 값. 비동기 색인 상태 추적용.
 */
public enum DocStatus {
	PENDING,
	PROCESSING,
	READY,
	FAILED
}
