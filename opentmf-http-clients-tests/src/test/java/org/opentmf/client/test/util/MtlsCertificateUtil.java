package org.opentmf.client.test.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public final class MtlsCertificateUtil {

  private static final String KEY_ALGORITHM = "RSA";
  private static final int KEY_SIZE = 2048;
  private static final String SIGNATURE_ALGORITHM = "SHA256WithRSAEncryption";
  private static final long TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000;

  private MtlsCertificateUtil() {
  }

  public static CertificateBundle generate() {
    try {
      var random = new SecureRandom();
      var keyPairGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
      keyPairGen.initialize(KEY_SIZE, random);

      var caKeyPair = keyPairGen.generateKeyPair();
      var caCert = createCaCertificate(caKeyPair, random);

      var clientKeyPair = keyPairGen.generateKeyPair();
      var clientCert = createClientCertificate(clientKeyPair, caKeyPair, caCert, random);

      var caCertFile = writeTempPem("mtls-ca-cert-", caCert);
      writeTempPem("mtls-ca-key-", caKeyPair.getPrivate());

      var clientCertFile = writeTempPem("mtls-client-cert-", clientCert);
      var clientKeyFile = writeTempPem("mtls-client-key-", clientKeyPair.getPrivate());

      var password = "testpass";

      var clientKeyStoreBase64 = createKeyStoreBase64(
          clientKeyPair, clientCert, caCert, password);
      var clientTrustStoreBase64 = createTrustStoreBase64(caCert, password);

      return new CertificateBundle(
          caCertFile,
          clientCertFile,
          clientKeyFile,
          clientKeyStoreBase64,
          clientTrustStoreBase64,
          password
      );
    } catch (Exception e) {
      throw new IllegalStateException("Failed to generate mTLS certificates", e);
    }
  }

  private static String writeTempPem(String prefix, Object obj) throws Exception {
    var file = Files.createTempFile(prefix, ".pem");
    Files.writeString(file, toPem(obj));
    return file.toAbsolutePath().toString();
  }

  private static X509Certificate createCaCertificate(KeyPair caKeyPair, SecureRandom random)
      throws Exception {
    var now = new Date();
    var notAfter = new Date(now.getTime() + TEN_YEARS_MS);
    var subject = new X500Principal("CN=Test CA, O=OpenTMF Test, L=Test");

    var builder = new JcaX509v3CertificateBuilder(
        subject,
        BigInteger.valueOf(random.nextLong()).abs(),
        now,
        notAfter,
        subject,
        caKeyPair.getPublic()
    );
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

    var signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(caKeyPair.getPrivate());
    X509CertificateHolder holder = builder.build(signer);
    return new JcaX509CertificateConverter().getCertificate(holder);
  }

  private static X509Certificate createClientCertificate(
      KeyPair clientKeyPair, KeyPair caKeyPair, X509Certificate caCert, SecureRandom random)
      throws Exception {
    var now = new Date();
    var notAfter = new Date(now.getTime() + TEN_YEARS_MS);
    var subject = new X500Principal("CN=Test Client, O=OpenTMF Test, L=Test");

    var builder = new JcaX509v3CertificateBuilder(
        caCert,
        BigInteger.valueOf(random.nextLong()).abs(),
        now,
        notAfter,
        subject,
        clientKeyPair.getPublic()
    );
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

    var signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(caKeyPair.getPrivate());
    X509CertificateHolder holder = builder.build(signer);
    return new JcaX509CertificateConverter().getCertificate(holder);
  }

  private static String toPem(Object obj) throws IOException {
    var sw = new StringWriter();
    try (var writer = new JcaPEMWriter(sw)) {
      writer.writeObject(obj);
    }
    return sw.toString();
  }

  private static String createKeyStoreBase64(
      KeyPair clientKeyPair, X509Certificate clientCert, X509Certificate caCert, String password)
      throws Exception {
    var ks = KeyStore.getInstance("JKS");
    ks.load(null, password.toCharArray());
    ks.setKeyEntry("client",
        clientKeyPair.getPrivate(),
        password.toCharArray(),
        new java.security.cert.Certificate[]{clientCert, caCert});
    return keyStoreToBase64(ks, password);
  }

  private static String createTrustStoreBase64(X509Certificate caCert, String password)
      throws Exception {
    var ks = KeyStore.getInstance("JKS");
    ks.load(null, password.toCharArray());
    ks.setCertificateEntry("ca", caCert);

    var mockServerCa = loadMockServerCaCertificate();
    if (mockServerCa != null) {
      ks.setCertificateEntry("mockserver-ca", mockServerCa);
    }
    return keyStoreToBase64(ks, password);
  }

  private static X509Certificate loadMockServerCaCertificate() throws Exception {
    String path = "/org/mockserver/socket/CertificateAuthorityCertificate.pem";
    try (InputStream is = MtlsCertificateUtil.class.getResourceAsStream(path)) {
      if (is == null) {
        return null;
      }
      var cf = CertificateFactory.getInstance("X.509");
      return (X509Certificate) cf.generateCertificate(is);
    }
  }

  private static String keyStoreToBase64(KeyStore ks, String password) throws Exception {
    var baos = new ByteArrayOutputStream();
    ks.store(baos, password.toCharArray());
    return Base64.getEncoder().encodeToString(baos.toByteArray());
  }

  public record CertificateBundle(
      String caCertPemPath,
      String clientCertPemPath,
      String clientKeyPemPath,
      String clientKeyStoreBase64,
      String clientTrustStoreBase64,
      String password
  ) {
  }
}
