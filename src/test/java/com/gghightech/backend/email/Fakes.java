package com.gghightech.backend.email;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.gghightech.backend.email.dns.TxtLookup;
import com.gghightech.backend.email.james.JamesAdmin;

/** In-memory stand-ins for James WebAdmin and public DNS. */
@TestConfiguration
public class Fakes {

    public static class FakeJames implements JamesAdmin {
        public final Set<String> domains = new HashSet<>();
        public final Map<String, String> users = new HashMap<>();
        public boolean down;

        @Override
        public void addDomain(String domain) {
            failIfDown();
            domains.add(domain);
        }

        @Override
        public void removeDomain(String domain) {
            failIfDown();
            domains.remove(domain);
        }

        @Override
        public void createUser(String address, String password) {
            failIfDown();
            users.put(address, password);
        }

        @Override
        public void deleteUser(String address) {
            failIfDown();
            users.remove(address);
        }

        private void failIfDown() {
            if (down) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "Could not reach the mail server");
            }
        }

        public void reset() {
            domains.clear();
            users.clear();
            down = false;
        }
    }

    public static class FakeDns implements TxtLookup {
        public final Map<String, List<String>> records = new HashMap<>();

        @Override
        public List<String> txt(String name) {
            return records.getOrDefault(name, List.of());
        }

        public void put(String name, String value) {
            records.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
    }

    @Bean
    @Primary
    FakeJames fakeJames() {
        return new FakeJames();
    }

    @Bean
    @Primary
    FakeDns fakeDns() {
        return new FakeDns();
    }
}
