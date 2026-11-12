package com.posplatform.modules.identity.infrastructure;

import com.posplatform.modules.identity.AppSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppSessionRepository extends JpaRepository<AppSession, UUID> {

    Optional<AppSession> findByTokenHash(String tokenHash);
}
