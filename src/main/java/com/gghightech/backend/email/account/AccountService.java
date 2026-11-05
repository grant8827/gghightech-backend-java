package com.gghightech.backend.email.account;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gghightech.backend.email.config.EmailProperties;
import com.gghightech.backend.email.domain.DomainService;
import com.gghightech.backend.email.domain.SendingDomain;
import com.gghightech.backend.email.james.JamesAdmin;

@Service
public class AccountService {
    private static final Pattern LOCAL_PART = Pattern.compile("^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record AccountView(UUID id, UUID domainId, String address, String description, String status,
            Instant createdAt, String createdBy, Instant revokedAt, String revokedBy) {
        static AccountView of(SmtpAccount a) {
            return new AccountView(a.getId(), a.getDomainId(), a.getAddress(), a.getDescription(), a.getStatus(),
                    a.getCreatedAt(), a.getCreatedBy(), a.getRevokedAt(), a.getRevokedBy());
        }
    }

    /** Returned once, at creation. The password is never stored here and can't be shown again. */
    public record NewCredential(AccountView account, String smtpHost, int smtpPort, String security,
            String username, String password) {
    }

    private final SmtpAccountRepository accounts;
    private final DomainService domains;
    private final JamesAdmin james;
    private final EmailProperties properties;

    public AccountService(SmtpAccountRepository accounts, DomainService domains, JamesAdmin james,
            EmailProperties properties) {
        this.accounts = accounts;
        this.domains = domains;
        this.james = james;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<AccountView> list(UUID domainId) {
        domains.find(domainId);
        return accounts.findByDomainIdOrderByCreatedAtDesc(domainId).stream().map(AccountView::of).toList();
    }

    @Transactional
    public NewCredential create(UUID domainId, String rawLocalPart, String description, String actor) {
        SendingDomain domain = domains.find(domainId);
        if (!domain.isVerified()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Verify the domain's DNS records before creating SMTP credentials");
        }
        String localPart = rawLocalPart == null ? "" : rawLocalPart.strip().toLowerCase();
        if (!LOCAL_PART.matcher(localPart).matches()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Use letters, digits, dots, hyphens or underscores (e.g. noreply)");
        }
        String address = localPart + "@" + domain.getName();
        if (accounts.findByAddress(address).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    address + " already exists (revoked addresses can't be reused)");
        }
        String password = newPassword();
        SmtpAccount account = accounts.save(new SmtpAccount(domainId, address, blankToNull(description), actor));
        james.createUser(address, password);
        return new NewCredential(AccountView.of(account), properties.smtpHost(), properties.smtpPort(), "STARTTLS",
                address, password);
    }

    /** Takes effect on the next login: James no longer knows the account. */
    @Transactional
    public AccountView revoke(UUID id, String actor) {
        SmtpAccount account = accounts.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SMTP account not found"));
        if (account.isActive()) {
            james.deleteUser(account.getAddress());
            account.revoke(actor);
        }
        return AccountView.of(account);
    }

    private static String newPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
