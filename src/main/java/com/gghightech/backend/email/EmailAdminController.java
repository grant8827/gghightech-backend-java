package com.gghightech.backend.email;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gghightech.backend.email.account.AccountService;
import com.gghightech.backend.email.account.AccountService.AccountView;
import com.gghightech.backend.email.account.AccountService.NewCredential;
import com.gghightech.backend.email.account.SmtpAccount;
import com.gghightech.backend.email.account.SmtpAccountRepository;
import com.gghightech.backend.email.config.EmailProperties;
import com.gghightech.backend.email.config.TokenAuthFilter;
import com.gghightech.backend.email.domain.DomainService;
import com.gghightech.backend.email.domain.DomainService.DomainView;
import com.gghightech.backend.email.domain.SendingDomain;
import com.gghightech.backend.email.domain.SendingDomainRepository;
import com.gghightech.backend.email.event.EventService;
import com.gghightech.backend.email.event.EventService.MessageView;

/**
 * The Email Server tab's API. Reached only through the FastAPI proxy, which
 * checks the caller is a SUPER_ADMIN (see TokenAuthFilter).
 */
@RestController
@RequestMapping("/api/v1/email")
public class EmailAdminController {

    public record CreateDomain(String name) {
    }

    public record CreateAccount(String localPart, String description) {
    }

    private final DomainService domains;
    private final AccountService accounts;
    private final EventService events;
    private final SendingDomainRepository domainRepository;
    private final SmtpAccountRepository accountRepository;
    private final EmailProperties properties;

    public EmailAdminController(DomainService domains, AccountService accounts, EventService events,
            SendingDomainRepository domainRepository, SmtpAccountRepository accountRepository,
            EmailProperties properties) {
        this.domains = domains;
        this.accounts = accounts;
        this.events = events;
        this.domainRepository = domainRepository;
        this.accountRepository = accountRepository;
        this.properties = properties;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domains", domainRepository.count());
        out.put("verifiedDomains", domainRepository.countByStatus(SendingDomain.VERIFIED));
        out.put("activeAccounts", accountRepository.countByStatus(SmtpAccount.ACTIVE));
        out.put("last24h", events.countsLast24h());
        out.put("smtpHost", properties.smtpHost());
        out.put("smtpPort", properties.smtpPort());
        return out;
    }

    @GetMapping("/domains")
    public List<DomainView> listDomains() {
        return domains.list();
    }

    @PostMapping("/domains")
    @ResponseStatus(HttpStatus.CREATED)
    public DomainView createDomain(@RequestBody CreateDomain body, HttpServletRequest request) {
        return domains.create(body.name(), actor(request));
    }

    @GetMapping("/domains/{id}")
    public DomainView getDomain(@PathVariable UUID id) {
        return domains.get(id);
    }

    @PostMapping("/domains/{id}/verify")
    public DomainView verifyDomain(@PathVariable UUID id) {
        return domains.verify(id);
    }

    @DeleteMapping("/domains/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDomain(@PathVariable UUID id, HttpServletRequest request) {
        domains.delete(id, actor(request));
    }

    @GetMapping("/domains/{id}/accounts")
    public List<AccountView> listAccounts(@PathVariable UUID id) {
        return accounts.list(id);
    }

    @PostMapping("/domains/{id}/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public NewCredential createAccount(@PathVariable UUID id, @RequestBody CreateAccount body,
            HttpServletRequest request) {
        return accounts.create(id, body.localPart(), body.description(), actor(request));
    }

    @PostMapping("/accounts/{id}/revoke")
    public AccountView revokeAccount(@PathVariable UUID id, HttpServletRequest request) {
        return accounts.revoke(id, actor(request));
    }

    @GetMapping("/messages")
    public List<MessageView> messages(@RequestParam(required = false) String domain,
            @RequestParam(defaultValue = "50") int limit) {
        return events.recentMessages(domain, limit);
    }

    private static String actor(HttpServletRequest request) {
        return (String) request.getAttribute(TokenAuthFilter.ACTOR_ATTRIBUTE);
    }
}
