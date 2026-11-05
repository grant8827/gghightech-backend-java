package com.gghightech.backend.email.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gghightech.backend.email.account.SmtpAccount;
import com.gghightech.backend.email.account.SmtpAccountRepository;
import com.gghightech.backend.email.config.EmailProperties;
import com.gghightech.backend.email.crypto.DkimKeyGenerator;
import com.gghightech.backend.email.crypto.KeyCipher;
import com.gghightech.backend.email.dns.DnsRecords;
import com.gghightech.backend.email.dns.DnsRecords.DnsRecord;
import com.gghightech.backend.email.dns.DnsRecords.RecordCheck;
import com.gghightech.backend.email.dns.TxtLookup;
import com.gghightech.backend.email.james.JamesAdmin;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class DomainService {
    // Lowercase labels of letters, digits and hyphens; at least one dot.
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    public record DomainView(UUID id, String name, String status, Instant createdAt, String createdBy,
            Instant verifiedAt, Instant lastCheckedAt, List<DnsRecord> dnsRecords, List<RecordCheck> lastCheck,
            long activeAccounts) {
    }

    private final SendingDomainRepository domains;
    private final SmtpAccountRepository accounts;
    private final DkimKeyGenerator keyGenerator;
    private final KeyCipher cipher;
    private final TxtLookup dns;
    private final JamesAdmin james;
    private final EmailProperties properties;
    private final ObjectMapper json;

    public DomainService(SendingDomainRepository domains, SmtpAccountRepository accounts,
            DkimKeyGenerator keyGenerator, KeyCipher cipher, TxtLookup dns, JamesAdmin james,
            EmailProperties properties, ObjectMapper json) {
        this.domains = domains;
        this.accounts = accounts;
        this.keyGenerator = keyGenerator;
        this.cipher = cipher;
        this.dns = dns;
        this.james = james;
        this.properties = properties;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<DomainView> list() {
        return domains.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public DomainView get(UUID id) {
        return view(find(id));
    }

    /** Saved first, then added to James; if James refuses, the transaction rolls back. */
    @Transactional
    public DomainView create(String rawName, String actor) {
        requireSpfMechanism();
        String name = normalize(rawName);
        if (!DOMAIN.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Not a valid domain name: " + rawName);
        }
        if (domains.findByName(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, name + " has already been added");
        }
        DkimKeyGenerator.DkimKeys keys = keyGenerator.generate();
        SendingDomain domain = domains.save(new SendingDomain(name, properties.dkimSelector(), keys.publicKey(),
                cipher.encrypt(keys.privatePem()), actor));
        james.addDomain(name);
        return view(domain);
    }

    /** Looks the records up in public DNS now and records the result. */
    @Transactional
    public DomainView verify(UUID id) {
        requireSpfMechanism();
        SendingDomain domain = find(id);
        List<RecordCheck> checks = DnsRecords.check(dns, domain.getName(), domain.getDkimSelector(),
                domain.getDkimPublicKey(), properties.spfMechanism());
        domain.recordCheck(checks.stream().allMatch(RecordCheck::ok), json.writeValueAsString(checks));
        return view(domain);
    }

    /** Revokes every SMTP account on the domain, then removes it from James and from here. */
    @Transactional
    public void delete(UUID id, String actor) {
        SendingDomain domain = find(id);
        for (SmtpAccount account : accounts.findByDomainIdOrderByCreatedAtDesc(id)) {
            if (account.isActive()) {
                james.deleteUser(account.getAddress());
            }
        }
        james.removeDomain(domain.getName());
        domains.delete(domain);
    }

    /** The decrypted signing key, for James. Empty if the domain isn't known. */
    @Transactional(readOnly = true)
    public Optional<String> dkimPrivateKey(String name) {
        return domains.findByName(normalize(name)).map(d -> cipher.decrypt(d.getDkimPrivateKeyEncrypted()));
    }

    public SendingDomain find(UUID id) {
        return domains.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Domain not found"));
    }

    private DomainView view(SendingDomain d) {
        List<RecordCheck> lastCheck = d.getLastCheckResult() == null ? List.of()
                : json.readValue(d.getLastCheckResult(), new TypeReference<List<RecordCheck>>() {
                });
        long active = accounts.findByDomainIdOrderByCreatedAtDesc(d.getId()).stream().filter(SmtpAccount::isActive)
                .count();
        return new DomainView(d.getId(), d.getName(), d.getStatus(), d.getCreatedAt(), d.getCreatedBy(),
                d.getVerifiedAt(), d.getLastCheckedAt(),
                DnsRecords.required(d.getName(), d.getDkimSelector(), d.getDkimPublicKey(), properties.spfMechanism()),
                lastCheck, active);
    }

    private void requireSpfMechanism() {
        if (properties.spfMechanism() == null || properties.spfMechanism().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "EMAIL_SPF_MECHANISM is not configured (e.g. ip4:<mail server IP>)");
        }
    }

    private static String normalize(String name) {
        String n = name == null ? "" : name.strip().toLowerCase();
        return n.endsWith(".") ? n.substring(0, n.length() - 1) : n;
    }
}
