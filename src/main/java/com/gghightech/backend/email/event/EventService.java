package com.gghightech.backend.email.event;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonProperty;

@Service
public class EventService {
    /** Every event the James mailets post (see james-poc/conf/mailetcontainer.xml). */
    public static final Set<String> EVENTS = Set.of("accepted", "delivered", "failed", "rejected_sender",
            "rate_limited", "relay_denied", "error");

    /** As posted by James's DeliveryEventNotifier mailet. */
    public record IncomingEvent(
            String event,
            @JsonProperty("mail_id") String mailId,
            @JsonProperty("message_id") String messageId,
            @JsonProperty("auth_user") String authUser,
            String sender,
            List<String> recipients,
            String error,
            String at) {
    }

    public record EventView(String event, Instant at, String error) {
    }

    /** One sent message with its history, newest message first. */
    public record MessageView(String mailId, String messageId, String account, String domain, String sender,
            List<String> recipients, String status, Instant firstSeen, Instant lastUpdate, List<EventView> history) {
    }

    private final DeliveryEventRepository events;

    public EventService(DeliveryEventRepository events) {
        this.events = events;
    }

    @Transactional
    public void record(IncomingEvent in) {
        if (in.event() == null || !EVENTS.contains(in.event()) || in.mailId() == null || in.mailId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown event or missing mail_id");
        }
        String authUser = blankToNull(in.authUser());
        String domain = authUser != null && authUser.contains("@") ? authUser.substring(authUser.indexOf('@') + 1) : null;
        String recipients = in.recipients() == null ? null : String.join(",", in.recipients());
        events.save(new DeliveryEvent(clip(in.mailId(), 255), clip(blankToNull(in.messageId()), 998), in.event(),
                clip(authUser, 320), clip(domain, 253), clip(blankToNull(in.sender()), 320), recipients,
                blankToNull(in.error()), parse(in.at())));
    }

    @Transactional(readOnly = true)
    public List<MessageView> recentMessages(String domain, int limit) {
        List<String> ids = events.recentMailIds(blankToNull(domain), PageRequest.of(0, Math.min(Math.max(limit, 1), 200)));
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, List<DeliveryEvent>> byMail = new LinkedHashMap<>();
        ids.forEach(id -> byMail.put(id, new ArrayList<>()));
        for (DeliveryEvent e : events.findByMailIdInOrderByOccurredAtAsc(ids)) {
            byMail.get(e.getMailId()).add(e);
        }
        List<MessageView> out = new ArrayList<>();
        for (List<DeliveryEvent> history : byMail.values()) {
            DeliveryEvent first = history.get(0);
            DeliveryEvent last = history.get(history.size() - 1);
            List<String> recipients = history.stream().map(DeliveryEvent::getRecipients)
                    .filter(r -> r != null && !r.isEmpty()).findFirst().map(r -> List.of(r.split(","))).orElse(List.of());
            out.add(new MessageView(first.getMailId(), first.getMessageId(), first.getAuthUser(), first.getDomain(),
                    first.getSender(), recipients, status(history), first.getOccurredAt(), last.getOccurredAt(),
                    history.stream().map(e -> new EventView(e.getEvent(), e.getOccurredAt(), e.getError())).toList()));
        }
        return out;
    }

    /** Last-24-hour counts per event, for the overview. */
    @Transactional(readOnly = true)
    public Map<String, Long> countsLast24h() {
        Map<String, Long> counts = new LinkedHashMap<>();
        EVENTS.stream().sorted().forEach(e -> counts.put(e, 0L));
        for (Object[] row : events.countByEventSince(Instant.now().minus(24, ChronoUnit.HOURS))) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    /**
     * delivered (and nothing failed) > partially delivered > failed > rejected
     * > still in the queue ("queued").
     */
    private static String status(List<DeliveryEvent> history) {
        boolean delivered = history.stream().anyMatch(e -> e.getEvent().equals("delivered"));
        boolean failed = history.stream().anyMatch(e -> e.getEvent().equals("failed") || e.getEvent().equals("error"));
        if (delivered && failed) {
            return "partially_delivered";
        }
        if (delivered) {
            return "delivered";
        }
        if (failed) {
            return "failed";
        }
        return history.stream().map(DeliveryEvent::getEvent)
                .filter(e -> e.startsWith("rejected") || e.equals("rate_limited") || e.equals("relay_denied"))
                .findFirst().orElse("queued");
    }

    private static Instant parse(String at) {
        try {
            return at == null || at.isBlank() ? Instant.now() : Instant.parse(at);
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
