package com.onggijonggi.common.chat.persistence;

import com.onggijonggi.common.chat.domain.MsgIdmKey;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Class Name : MsgIdmKeyRepository.java
 * Description : msg_idm_key JPA 레포지토리.
 */
public interface MsgIdmKeyRepository extends JpaRepository<MsgIdmKey, UUID> {

	Optional<MsgIdmKey> findByUserIdAndKey(UUID userId, String key);

}
