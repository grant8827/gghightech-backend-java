package com.gghightech.backend.email.dns;

import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import com.gghightech.backend.email.config.EmailProperties;

/**
 * Asks public resolvers directly (EMAIL_DNS_RESOLVERS, default Cloudflare and
 * Google), with no cache, so "Verify" reflects what the rest of the internet
 * sees right now, not what this server looked up earlier.
 */
@Component
public class DnsJavaTxtLookup implements TxtLookup {
    private final Resolver resolver;

    public DnsJavaTxtLookup(EmailProperties properties) {
        try {
            List<Resolver> resolvers = new ArrayList<>();
            for (String entry : properties.dnsResolvers()) {
                String[] hostPort = entry.strip().split(":");
                SimpleResolver simple = new SimpleResolver(hostPort[0]);
                if (hostPort.length > 1) {
                    simple.setPort(Integer.parseInt(hostPort[1]));
                }
                resolvers.add(simple);
            }
            ExtendedResolver extended = new ExtendedResolver(resolvers);
            extended.setTimeout(Duration.ofSeconds(4));
            this.resolver = extended;
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Bad EMAIL_DNS_RESOLVERS", e);
        }
    }

    @Override
    public List<String> txt(String name) {
        try {
            Lookup lookup = new Lookup(Name.fromString(name.endsWith(".") ? name : name + "."), Type.TXT);
            lookup.setResolver(resolver);
            lookup.setCache(null);
            Record[] records = lookup.run();
            if (records == null) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (Record record : records) {
                if (record instanceof TXTRecord txt) {
                    values.add(String.join("", txt.getStrings()));
                }
            }
            return values;
        } catch (TextParseException e) {
            return List.of();
        }
    }
}
