package com.onggijonggi.bff.doc;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : DocRepository.java
 * Description : doc JPA 레포지토리. 파생 쿼리는 아직 없다 — 호출부(업로드 API·색인 워커)가
 *               없어 지금은 필요 없다(투기적 코드 방지). 필요해지면 그때 추가한다.
 */
public interface DocRepository extends JpaRepository<Doc, UUID> {
}
