package com.gghightech.backend.email.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SendingDomainRepository extends JpaRepository<SendingDomain, UUID> {
    Optional<SendingDomain> findByName(String name);

    List<SendingDomain> findAllByOrderByNameAsc();

    long countByStatus(String status);
}
