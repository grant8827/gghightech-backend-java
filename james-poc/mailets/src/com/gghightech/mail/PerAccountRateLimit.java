package com.gghightech.mail;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.mail.MessagingException;

import org.apache.mailet.Mail;
import org.apache.mailet.base.GenericMailet;

/**
 * Caps messages per SMTP account per minute (sliding window, in memory).
 * Over the cap, the mail goes to exceededProcessor instead of out.
 *
 * PoC limitation: by this point the message is already accepted. The
 * production version should refuse at SMTP time (a 451 from an SMTP hook)
 * so the sending app gets an error it can retry.
 *
 * Params: maxPerMinute, exceededProcessor.
 */
public class PerAccountRateLimit extends GenericMailet {
    private int maxPerMinute;
    private String exceededProcessor;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public void init() throws MessagingException {
        maxPerMinute = Integer.parseInt(getInitParameter("maxPerMinute"));
        exceededProcessor = getInitParameter("exceededProcessor");
    }

    @Override
    public void service(Mail mail) throws MessagingException {
        String account = SmtpAuth.user(mail).orElse("anonymous");
        long now = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(account, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) {
                window.pollFirst();
            }
            if (window.size() >= maxPerMinute) {
                mail.setErrorMessage("Rate limit of " + maxPerMinute + "/min exceeded for " + account);
                mail.setState(exceededProcessor);
                return;
            }
            window.addLast(now);
        }
    }

    @Override
    public String getMailetInfo() {
        return "GG HighTech per-account rate limit";
    }
}
