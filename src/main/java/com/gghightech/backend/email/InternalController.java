package com.gghightech.backend.email;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gghightech.backend.email.domain.DomainService;
import com.gghightech.backend.email.event.EventService;
import com.gghightech.backend.email.event.EventService.IncomingEvent;

/** Called by the mail server only, with the internal token (see TokenAuthFilter). */
@RestController
@RequestMapping("/internal/v1")
public class InternalController {
    private final EventService events;
    private final DomainService domains;

    public InternalController(EventService events, DomainService domains) {
        this.events = events;
        this.domains = domains;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordEvent(@RequestBody IncomingEvent event) {
        events.record(event);
    }

    /** PKCS#1 PEM for James's DynamicDkimSign mailet. */
    @GetMapping(value = "/dkim/{domain}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> dkimKey(@PathVariable String domain) {
        return domains.dkimPrivateKey(domain).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
