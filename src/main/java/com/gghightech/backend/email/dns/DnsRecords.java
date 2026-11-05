package com.gghightech.backend.email.dns;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The DNS records a sending domain needs, and the check for each. SPF and
 * DMARC values are a starting point: a domain that already has an SPF
 * record only needs our mechanism added to it, and any existing DMARC
 * record counts.
 */
public final class DnsRecords {
    private DnsRecords() {
    }

    public record DnsRecord(String purpose, String type, String name, String value, String hint) {
    }

    public record RecordCheck(String purpose, String name, boolean ok, List<String> found, String problem) {
    }

    public static List<DnsRecord> required(String domain, String selector, String publicKey, String spfMechanism) {
        return List.of(
                new DnsRecord("SPF", "TXT", domain, "v=spf1 " + spfMechanism + " ~all",
                        "Already have an SPF record? Add \"" + spfMechanism + "\" to it instead of creating a second one."),
                new DnsRecord("DKIM", "TXT", selector + "._domainkey." + domain, "v=DKIM1; k=rsa; p=" + publicKey,
                        "Long value: paste it whole; your DNS provider may split it into parts, which is fine."),
                new DnsRecord("DMARC", "TXT", "_dmarc." + domain, "v=DMARC1; p=none; rua=mailto:dmarc@" + domain,
                        "Start with p=none. Move to p=quarantine once mail is landing in inboxes."));
    }

    public static List<RecordCheck> check(TxtLookup dns, String domain, String selector, String publicKey,
            String spfMechanism) {
        List<RecordCheck> checks = new ArrayList<>();

        List<String> spf = dns.txt(domain).stream().filter(v -> v.toLowerCase().startsWith("v=spf1")).toList();
        String spfProblem = spf.isEmpty() ? "No SPF record found"
                : spf.size() > 1 ? "More than one SPF record; receivers treat that as an error. Merge them into one"
                        : !hasMechanism(spf.get(0), spfMechanism) ? "SPF record doesn't include " + spfMechanism : null;
        checks.add(new RecordCheck("SPF", domain, spfProblem == null, spf, spfProblem));

        String dkimName = selector + "._domainkey." + domain;
        List<String> dkim = dns.txt(dkimName);
        boolean dkimOk = dkim.stream().map(DnsRecords::tags).anyMatch(publicKeyIs(publicKey));
        checks.add(new RecordCheck("DKIM", dkimName, dkimOk, dkim,
                dkimOk ? null : dkim.isEmpty() ? "No DKIM record found" : "DKIM record doesn't match this domain's key"));

        String dmarcName = "_dmarc." + domain;
        List<String> dmarc = dns.txt(dmarcName).stream().filter(v -> v.toLowerCase().startsWith("v=dmarc1")).toList();
        checks.add(new RecordCheck("DMARC", dmarcName, !dmarc.isEmpty(), dmarc,
                dmarc.isEmpty() ? "No DMARC record found" : null));
        return checks;
    }

    private static boolean hasMechanism(String spf, String mechanism) {
        for (String term : spf.toLowerCase().split("\\s+")) {
            if (term.replaceFirst("^[+]", "").equals(mechanism.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static Predicate<Map<String, String>> publicKeyIs(String publicKey) {
        return tags -> publicKey.equals(tags.getOrDefault("p", ""));
    }

    /** "v=DKIM1; k=rsa; p=AB CD" -> {v: DKIM1, k: rsa, p: ABCD} (whitespace removed from values). */
    private static Map<String, String> tags(String record) {
        Map<String, String> tags = new java.util.HashMap<>();
        for (String part : record.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                tags.put(part.substring(0, eq).strip().toLowerCase(), part.substring(eq + 1).replaceAll("\\s+", ""));
            }
        }
        return tags;
    }
}
