package com.gghightech.backend.email.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SmtpAccountRepository extends JpaRepository<SmtpAccount, UUID> {
    List<SmtpAccount> findByDomainIdOrderByCreatedAtDesc(UUID domainId);

    Optional<SmtpAccount> findByAddress(String address);

    long countByStatus(String status);
}
