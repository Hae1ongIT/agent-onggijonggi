package com.onggijonggi.bff.doc;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : DocVerRepository.java
 * Description : doc_ver JPA 레포지토리. 파생 쿼리는 아직 없다 — 호출부가 없어 지금은 필요 없다
 *               (투기적 코드 방지). 필요해지면 그때 추가한다.
 */
public interface DocVerRepository extends JpaRepository<DocVer, UUID> {
}
