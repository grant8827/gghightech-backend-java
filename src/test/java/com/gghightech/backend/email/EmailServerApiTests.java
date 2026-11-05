package com.gghightech.backend.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.gghightech.backend.email.account.SmtpAccountRepository;
import com.gghightech.backend.email.domain.SendingDomainRepository;
import com.gghightech.backend.email.event.DeliveryEventRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(Fakes.class)
class EmailServerApiTests {
    private static final String API = "/api/v1/email";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    Fakes.FakeJames james;
    @Autowired
    Fakes.FakeDns dns;
    @Autowired
    SendingDomainRepository domainRepo;
    @Autowired
    SmtpAccountRepository accountRepo;
    @Autowired
    DeliveryEventRepository eventRepo;

    @BeforeEach
    void clean() {
        eventRepo.deleteAll();
        accountRepo.deleteAll();
        domainRepo.deleteAll();
        james.reset();
        dns.records.clear();
    }

    // ------------------------------------------------------------ helpers

    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("X-Service-Token", "test-service-token").header("X-Actor-Email", "admin@gghightech.test");
    }

    MockHttpServletRequestBuilder mailServer(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer test-internal-token");
    }

    JsonNode call(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse()
                .getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    JsonNode addDomain(String name) throws Exception {
        return call(admin(post(API + "/domains")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name))), 201);
    }

    /** Publishes exactly the records the dashboard told the admin to create. */
    void publishRecords(JsonNode domain) {
        for (JsonNode record : domain.get("dnsRecords")) {
            dns.put(record.get("name").asString(), record.get("value").asString());
        }
    }

    JsonNode verifiedDomain(String name) throws Exception {
        JsonNode domain = addDomain(name);
        publishRecords(domain);
        return call(admin(post(API + "/domains/" + domain.get("id").asString() + "/verify")), 200);
    }

    JsonNode createAccount(String domainId, String localPart, int expectedStatus) throws Exception {
        return call(admin(post(API + "/domains/" + domainId + "/accounts")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("localPart", localPart, "description", "HRBooks360 app"))),
                expectedStatus);
    }

    // ------------------------------------------------------------ auth

    @Test
    void adminApiNeedsTheServiceToken() throws Exception {
        mvc.perform(get(API + "/domains")).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/domains").header("X-Service-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/domains").header("Authorization", "Bearer test-internal-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(admin(get(API + "/domains"))).andExpect(status().isOk());
    }

    @Test
    void internalApiNeedsTheInternalToken() throws Exception {
        mvc.perform(get("/internal/v1/dkim/x.test")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/dkim/x.test").header("X-Service-Token", "test-service-token"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ domains

    @Test
    void addingADomainRegistersItWithJamesAndListsTheDnsRecords() throws Exception {
        JsonNode domain = addDomain("HRBooks360.com.");

        assertThat(domain.get("name").asString()).isEqualTo("hrbooks360.com");
        assertThat(domain.get("status").asString()).isEqualTo("PENDING_DNS");
        assertThat(domain.get("createdBy").asString()).isEqualTo("admin@gghightech.test");
        assertThat(james.domains).containsExactly("hrbooks360.com");

        List<String> names = domain.get("dnsRecords").valueStream().map(r -> r.get("name").asString()).toList();
        assertThat(names).containsExactly("hrbooks360.com", "gg1._domainkey.hrbooks360.com", "_dmarc.hrbooks360.com");
        String spf = domain.get("dnsRecords").get(0).get("value").asString();
        assertThat(spf).isEqualTo("v=spf1 ip4:203.0.113.10 ~all");
        assertThat(domain.get("dnsRecords").get(1).get("value").asString()).startsWith("v=DKIM1; k=rsa; p=MIIB");
    }

    @Test
    void invalidAndDuplicateDomainsAreRefused() throws Exception {
        for (String bad : List.of("localhost", "no spaces.com", "-bad.com", "a..b.com", "")) {
            call(admin(post(API + "/domains")).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", bad))), 422);
        }
        addDomain("shop.test");
        call(admin(post(API + "/domains")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "SHOP.test"))), 409);
    }

    @Test
    void ifJamesIsDownTheDomainIsNotSaved() throws Exception {
        james.down = true;
        call(admin(post(API + "/domains")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "shop.test"))), 502);
        assertThat(domainRepo.count()).isZero();
    }

    @Test
    void verificationPassesOnlyWhenAllThreeRecordsAreLive() throws Exception {
        JsonNode domain = addDomain("shop.test");
        String id = domain.get("id").asString();

        JsonNode checked = call(admin(post(API + "/domains/" + id + "/verify")), 200);
        assertThat(checked.get("status").asString()).isEqualTo("PENDING_DNS");
        assertThat(checked.get("lastCheck").valueStream().map(c -> c.get("problem").asString()).toList())
                .containsExactly("No SPF record found", "No DKIM record found", "No DMARC record found");

        publishRecords(domain);
        checked = call(admin(post(API + "/domains/" + id + "/verify")), 200);
        assertThat(checked.get("status").asString()).isEqualTo("VERIFIED");
        assertThat(checked.get("verifiedAt").isNull()).isFalse();
    }

    @Test
    void anExistingSpfRecordJustNeedsOurMechanism() throws Exception {
        JsonNode domain = addDomain("shop.test");
        publishRecords(domain);
        dns.records.put("shop.test", List.of("v=spf1 include:_spf.google.com ip4:203.0.113.10 ~all"));
        JsonNode checked = call(admin(post(API + "/domains/" + domain.get("id").asString() + "/verify")), 200);
        assertThat(checked.get("status").asString()).isEqualTo("VERIFIED");
    }

    @Test
    void wrongSpfOrDkimIsReportedPrecisely() throws Exception {
        JsonNode domain = addDomain("shop.test");
        publishRecords(domain);
        dns.records.put("shop.test", List.of("v=spf1 include:_spf.google.com ~all", "v=spf1 a ~all"));
        dns.records.put("gg1._domainkey.shop.test", List.of("v=DKIM1; k=rsa; p=SOMEONEELSESKEY"));
        JsonNode checked = call(admin(post(API + "/domains/" + domain.get("id").asString() + "/verify")), 200);
        assertThat(checked.get("lastCheck").get(0).get("problem").asString()).contains("More than one SPF record");
        assertThat(checked.get("lastCheck").get(1).get("problem").asString())
                .isEqualTo("DKIM record doesn't match this domain's key");
    }

    @Test
    void theMailServerCanFetchTheDecryptedSigningKey() throws Exception {
        JsonNode domain = addDomain("shop.test");
        String pem = mvc.perform(mailServer(get("/internal/v1/dkim/shop.test"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(pem).startsWith("-----BEGIN RSA PRIVATE KEY-----");
        assertThat(domainRepo.findById(java.util.UUID.fromString(domain.get("id").asString())).orElseThrow()
                .getDkimPrivateKeyEncrypted()).doesNotContain("PRIVATE KEY");
        mvc.perform(mailServer(get("/internal/v1/dkim/unknown.test"))).andExpect(status().isNotFound());
    }

    @Test
    void deletingADomainRemovesItsAccountsFromJames() throws Exception {
        JsonNode domain = verifiedDomain("shop.test");
        String id = domain.get("id").asString();
        createAccount(id, "orders", 201);
        call(admin(delete(API + "/domains/" + id)), 204);
        assertThat(james.domains).isEmpty();
        assertThat(james.users).isEmpty();
        assertThat(accountRepo.count()).isZero();
    }

    // ------------------------------------------------------------ accounts

    @Test
    void credentialsNeedAVerifiedDomain() throws Exception {
        JsonNode domain = addDomain("shop.test");
        JsonNode error = createAccount(domain.get("id").asString(), "orders", 409);
        assertThat(james.users).isEmpty();
    }

    @Test
    void aNewCredentialIsShownOnceWithTheSmtpSettings() throws Exception {
        JsonNode domain = verifiedDomain("shop.test");
        String id = domain.get("id").asString();

        JsonNode credential = createAccount(id, "Orders", 201);
        assertThat(credential.get("username").asString()).isEqualTo("orders@shop.test");
        assertThat(credential.get("smtpHost").asString()).isEqualTo("mail.example.test");
        assertThat(credential.get("smtpPort").asInt()).isEqualTo(587);
        String password = credential.get("password").asString();
        assertThat(password).hasSizeGreaterThanOrEqualTo(32);
        assertThat(james.users).containsEntry("orders@shop.test", password);

        // Listing never includes the password.
        JsonNode listed = call(admin(get(API + "/domains/" + id + "/accounts")), 200);
        assertThat(listed.get(0).has("password")).isFalse();
        assertThat(listed.get(0).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void badOrDuplicateAddressesAreRefused() throws Exception {
        String id = verifiedDomain("shop.test").get("id").asString();
        for (String bad : List.of("", "a b", "x@y", ".dot", "dot.", "toolong-" + "x".repeat(60))) {
            createAccount(id, bad, 422);
        }
        createAccount(id, "orders", 201);
        createAccount(id, "orders", 409);
    }

    @Test
    void revokingRemovesTheLoginFromJames() throws Exception {
        String id = verifiedDomain("shop.test").get("id").asString();
        String accountId = createAccount(id, "orders", 201).get("account").get("id").asString();

        JsonNode revoked = call(admin(post(API + "/accounts/" + accountId + "/revoke")), 200);
        assertThat(revoked.get("status").asString()).isEqualTo("REVOKED");
        assertThat(revoked.get("revokedBy").asString()).isEqualTo("admin@gghightech.test");
        assertThat(james.users).isEmpty();
        // Revoked addresses can't be recreated (keeps the audit trail unambiguous).
        createAccount(id, "orders", 409);
    }

    // ------------------------------------------------------------ events

    void postEvent(String mailId, String event, String recipient, String error) throws Exception {
        Map<String, Object> body = Map.of("event", event, "mail_id", mailId, "message_id", "<" + mailId + "@shop.test>",
                "auth_user", "orders@shop.test", "sender", "orders@shop.test", "recipients", List.of(recipient),
                "error", error, "at", java.time.Instant.now().toString());
        mvc.perform(mailServer(post("/internal/v1/events")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isNoContent());
    }

    @Test
    void eventsBuildEachMessagesHistoryAndStatus() throws Exception {
        postEvent("m1", "accepted", "a@gmail.test", "");
        postEvent("m1", "delivered", "a@gmail.test", "");
        postEvent("m2", "accepted", "b@gmail.test", "");
        postEvent("m2", "failed", "b@gmail.test", "550 5.1.1 No such user here");
        postEvent("m3", "accepted", "c@gmail.test", "");

        JsonNode messages = call(admin(get(API + "/messages")), 200);
        Map<String, String> statusById = new java.util.HashMap<>();
        messages.forEach(m -> statusById.put(m.get("mailId").asString(), m.get("status").asString()));
        assertThat(statusById).containsEntry("m1", "delivered").containsEntry("m2", "failed").containsEntry("m3", "queued");

        JsonNode m2 = messages.valueStream().filter(m -> m.get("mailId").asString().equals("m2")).findFirst().orElseThrow();
        assertThat(m2.get("domain").asString()).isEqualTo("shop.test");
        assertThat(m2.get("history").get(1).get("error").asString()).contains("No such user");

        JsonNode overview = call(admin(get(API + "/overview")), 200);
        assertThat(overview.get("last24h").get("accepted").asLong()).isEqualTo(3);
        assertThat(overview.get("last24h").get("failed").asLong()).isEqualTo(1);
    }

    @Test
    void unknownEventsAreRejected() throws Exception {
        mvc.perform(mailServer(post("/internal/v1/events")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"hacked\",\"mail_id\":\"m1\"}")).andExpect(status().isUnprocessableContent());
    }
}
