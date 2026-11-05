package com.gghightech.backend.email.crypto;

import java.io.IOException;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.springframework.stereotype.Component;

@Component
public class DkimKeyGenerator {

    /**
     * @param publicKey  base64 DER SubjectPublicKeyInfo, the DKIM record's p= value
     * @param privatePem PKCS#1 PEM ("BEGIN RSA PRIVATE KEY"), the only format
     *                   James's DKIMSign reads
     */
    public record DkimKeys(String publicKey, String privatePem) {
    }

    public DkimKeys generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            byte[] pkcs1 = PrivateKeyInfo.getInstance(pair.getPrivate().getEncoded()).parsePrivateKey()
                    .toASN1Primitive().getEncoded();
            StringWriter out = new StringWriter();
            try (PemWriter writer = new PemWriter(out)) {
                writer.writeObject(new PemObject("RSA PRIVATE KEY", pkcs1));
            }
            return new DkimKeys(publicKey, out.toString());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Could not generate a DKIM key", e);
        }
    }
}
