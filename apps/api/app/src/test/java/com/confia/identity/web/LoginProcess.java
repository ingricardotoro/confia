package com.confia.identity.web;

import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.token.AccessTokenVerifier;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import com.confia.support.SharedPostgresContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.Context;
import org.apache.catalina.Container;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The real administrative process, started through the production launcher against the shared
 * PostgreSQL with the least-privilege role of the application, as the login tests need it
 * (session-endpoints-cookie-and-csrf design.md decision 11). Everything the process holds that a
 * test must know is generated for the run and handed to the process: the signing key pair (so the
 * test verifies the tokens the process signs), the pepper (so the test stores password hashes the
 * process can check) and the login institution. The loopback is a trusted proxy, so a test chooses
 * the address of each request with {@code X-Forwarded-For} and the limiter sees a client of its own.
 */
final class LoginProcess implements AutoCloseable {

    static final String ROUTE = "/api/v1/auth/sessions";
    static final String ORIGIN = AdminProcessLauncher.ALLOWED_ORIGIN;
    static final String TRUST_LOOPBACK = "127.0.0.1,::1";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    private final ConfigurableApplicationContext context;
    private final int port;
    private final AccessTokenVerifier verifier;
    private final Argon2Pepper pepper;
    private final InstitutionId institution;

    private LoginProcess(ConfigurableApplicationContext context, AccessTokenVerifier verifier,
            Argon2Pepper pepper, InstitutionId institution) {
        this.context = context;
        this.port = ((WebServerApplicationContext) context).getWebServer().getPort();
        this.verifier = verifier;
        this.pepper = pepper;
        this.institution = institution;
    }

    /**
     * Starts the process with the pepper and the institution of the caller and the extra properties
     * given (a rate-limit policy, a Spring profile).
     */
    static LoginProcess start(byte[] pepperBytes, InstitutionId institution,
            String databasePassword, Map<String, String> extra) {
        KeyPair keys = newKeys();
        CompactJws jws = new CompactJws(SigningKeyRing.of(SigningKey.signing(
                AdminProcessLauncher.signingKid(), keys.getPublic(), keys.getPrivate())));
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("confia.identity.login-institution-id", institution.value().toString());
        properties.put("confia.identity.argon2-pepper",
                Base64.getEncoder().encodeToString(pepperBytes));
        properties.put("confia.web.trusted-proxies", TRUST_LOOPBACK);
        properties.putAll(extra);
        ConfigurableApplicationContext context = AdminProcessLauncher.launch(
                SharedPostgresContainer.jdbcUrl(), "confia_admin_app", databasePassword, keys,
                properties);
        return new LoginProcess(context, new AccessTokenVerifier(jws, Clock.systemUTC()),
                Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(pepperBytes)),
                institution);
    }

    static byte[] newPepperBytes() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** A client address no other request of the run has used, so no test shares a bucket. */
    static String freshAddress() {
        int n = ADDRESSES.incrementAndGet();
        return "10.77." + (n / 250) + "." + (n % 250 + 1);
    }

    AccessTokenVerifier verifier() {
        return verifier;
    }

    Argon2Pepper pepper() {
        return pepper;
    }

    InstitutionId institution() {
        return institution;
    }

    ConfigurableApplicationContext context() {
        return context;
    }

    /** A login request with the allowed origin and a JSON body, from the given client address. */
    HttpResponse<String> login(String address, String identifier, String password) {
        return post(jsonOf(identifier, password), "X-Forwarded-For", address);
    }

    /**
     * Posts {@code body} to the login route with the allowed {@code Origin} and {@code
     * Content-Type: application/json}; the extra headers come after and win.
     */
    HttpResponse<String> post(String body, String... headers) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("Origin", ORIGIN);
        all.put("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) {
            all.put(headers[i], headers[i + 1]);
        }
        return send("POST", body, all);
    }

    /** Posts with exactly the headers given. */
    HttpResponse<String> postRaw(String body, String... headers) {
        Map<String, String> all = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            all.put(headers[i], headers[i + 1]);
        }
        return send("POST", body, all);
    }

    HttpResponse<String> send(String method, String body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + ROUTE))
                .timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static String jsonOf(String identifier, String password) {
        return "{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}";
    }

    /** How many HTTP sessions the servlet container has created since the process started. */
    long createdHttpSessions() {
        TomcatWebServer server = (TomcatWebServer) ((WebServerApplicationContext) context)
                .getWebServer();
        long created = 0;
        for (Container host : server.getTomcat().getEngine().findChildren()) {
            for (Container child : host.findChildren()) {
                if (child instanceof Context application && application.getManager() != null) {
                    created += application.getManager().getSessionCounter();
                }
            }
        }
        return created;
    }

    @Override
    public void close() {
        context.close();
    }

    private static KeyPair newKeys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
