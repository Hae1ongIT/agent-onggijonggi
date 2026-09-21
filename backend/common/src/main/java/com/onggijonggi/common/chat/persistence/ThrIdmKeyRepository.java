package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.ThrIdmKey;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Class Name : ThrIdmKeyRepository.java
 * Description : thr_idm_key JPA 레포지토리.
 */
public interface ThrIdmKeyRepository extends JpaRepository<ThrIdmKey, UUID> {

	Optional<ThrIdmKey> findByUserIdAndKey(UUID userId, String key);

	/**
	* TTL 만료된 키를 지우고 바로 같은 트랜잭션에서 새 키를 저장하면, Hibernate가 flush 시
	* 모든 INSERT를 모든 DELETE보다 먼저 실행해(자바 코드 순서와 무관) 유니크 인덱스
	* (user_id, idm_key)와 충돌한다. 이 벌크 쿼리는 영속성 컨텍스트를 거치지 않고
	* executeUpdate()로 즉시 SQL을 내 그 순서 문제를 우회한다 — ThrMbrRepository.transferOwnership과
	* 같은 이유의 같은 패턴이다.
	*/
	@Modifying(clearAutomatically = true)
	@Transactional
	@Query("delete from ThrIdmKey k where k.userId = :userId and k.key = :key")
	void deleteImmediatelyByUserIdAndKey(@Param("userId") UUID userId, @Param("key") String key);

}
