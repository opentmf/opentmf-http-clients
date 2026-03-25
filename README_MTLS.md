# opentmf-http-clients Mutual TLS Support

To enable Mutual TLS handshake for opentmf HTTP clients, provide the Java Key Store (JKS) and optionally a truststore in the configuration. This works for all auth types (bearer, basic, or no auth) and for both reactive and REST clients.

> **Note:** mTLS can be used together with a forward proxy (HTTP CONNECT tunneling). Because the proxy merely relays encrypted bytes, the TLS handshake — including mutual certificate exchange — happens end-to-end between the client and the target server.

Steps to generate and provide a JKS with a signed certificate:

1. Clarify parameters for the certificate
2. Create a keystore
3. Create a CSR
4. Send the CSR to the CA
5. Import the signed certificate to the keystore
6. Configure the client to use the keystore
7. Test the connection

Steps to provide a truststore:

1. Obtain the server certificate
2. Export it into a JKS file
3. Configure the client to use the truststore
4. Test the connection

### Clarify parameters for the certificate

Please clarify the following parameters:
- CN = Common Name
- OU = Organizational Unit
- O = Organization
- L = Locality
- S = State or Province Name
- C = Country Name

The Common Name is especially important as it will be used to identify the client.

### Create Keystore

```bash
keytool -genkey -alias client -keyalg RSA -keysize 4096 -validity 730 \
-keypass mypassword \
-storetype pkcs12 \
-keystore my_keystore.jks \
-storepass mypassword \
-dname "CN=mycompany.com, OU=IT, O=MyOrg, L=MyLocation, S=MyState, C=Dreamland, EMAILADDRESS="
```

### Create CSR (Certificate Signing Request)

```bash
keytool -certreq -v -alias client \
-file my_keystore.csr \
-keystore my_keystore.jks \
-storepass mypassword
```

### Send the CSR to the CA

The CSR should be sent to the Certificate Authority to be signed. The CA will return a signed certificate (at least a P7B PEM file).

### Import the signed certificate to the keystore

Once you receive the signed certificate, import it as a trusted certificate into the JKS.

```bash
keytool -import -trustcacerts -alias client \
-file cert.p7b.pem \
-keystore my_keystore.jks \
-storepass mypassword
```

### Provide the JKS in Base64-encoded format

The keystore must be encoded in Base64 format. This is because Kubernetes does not support binary data in configuration files. The library will decode it back to binary format when loading.

```bash
base64 -w0 cert.jks > cert.jks.base64
```

Please note that you must use `-w0` to disable line wrapping.

If you need to provide it in sealed secrets for Kubernetes environments, encode it in Base64 one more time, as sealed secrets will decode once when attaching to pods:

```bash
base64 -w0 cert.jks.base64 > cert.jks.base64.base64
```

### Configure the client to use the keystore

Provide the keystore and optional truststore in the `certificates` block:

```yaml
opentmf:
  clients:
    myClient:
      bearer-auth:
        token-url: https://auth.example.com/token
        form-data:
          grant_type: client_credentials
      certificates:
        key-store:
          password: mypassword
          pk-password: mypassword
          base64-jks: <BASE64_JKS_CONTENT>
        trust-store:
          password: mypassword
          base64-jks: <BASE64_JKS_CONTENT>
```

This works the same for basic-auth, bearer-auth, or no-auth clients, and for both reactive and REST client types.

## Provide Truststore JKS file

The keystore holds the client certificate information (private key and certificate). The truststore holds the server certificate information.

If the server certificate was signed by a globally trusted CA registered in Java's cacerts, you do not need to provide a truststore explicitly.

Otherwise, obtain the server certificate, encode it in Base64, and set it in the truststore configuration:

```bash
keytool -import -file cert.crt -keypass mypassword -keystore truststore-cert.jks -storepass mypassword
```

Then encode the truststore JKS file in Base64:

```bash
base64 -w0 truststore-cert.jks > truststore-cert.jks.base64
```

For sealed secrets, encode in Base64 one more time:

```bash
base64 -w0 truststore-cert.jks.base64 > truststore-cert.jks.base64.base64
```

Then set the `trust-store.base64-jks` parameter in your client configuration.
