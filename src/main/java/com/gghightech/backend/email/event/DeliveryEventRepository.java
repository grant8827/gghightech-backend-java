package com.gghightech.backend.email.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryEventRepository extends JpaRepository<DeliveryEvent, UUID> {

    /** The newest mail ids, optionally for one domain. */
    @Query("""
            select e.mailId from DeliveryEvent e
            where (:domain is null or e.domain = :domain)
            group by e.mailId
            order by max(e.occurredAt) desc""")
    List<String> recentMailIds(@Param("domain") String domain, Pageable page);

    List<DeliveryEvent> findByMailIdInOrderByOccurredAtAsc(List<String> mailIds);

    @Query("select e.event, count(e) from DeliveryEvent e where e.occurredAt >= :since group by e.event")
    List<Object[]> countByEventSince(@Param("since") Instant since);
}
