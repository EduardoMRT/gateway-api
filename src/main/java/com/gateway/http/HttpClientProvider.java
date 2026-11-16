package com.gateway.http;

import com.gateway.config.GatewayProperties;
import com.gateway.config.GatewayProperties.Host;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import org.springframework.stereotype.Component;

/** Um {@link HttpClient} por host, montado conforme a config de SSL daquele host. */
@Component
public class HttpClientProvider {

    private final GatewayProperties props;
    private final Map<String, HttpClient> cache = new ConcurrentHashMap<>();

    public HttpClientProvider(GatewayProperties props) {
        this.props = props;
    }

    public HttpClient forHost(String host) {
        return cache.computeIfAbsent(host, this::build);
    }

    private HttpClient build(String host) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER);
        Host cfg = props.hosts().get(host);
        if (cfg != null && (cfg.insecure() || cfg.trustStore() != null || cfg.keyStore() != null)) {
            builder.sslContext(sslContext(cfg));
        }
        return builder.build();
    }

    private SSLContext sslContext(Host cfg) {
        try {
            KeyManager[] keyManagers = null;
            if (cfg.keyStore() != null) {
                KeyStore ks = load(cfg.keyStore(), cfg.keyStorePassword());
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, chars(cfg.keyStorePassword()));
                keyManagers = kmf.getKeyManagers();
            }
            TrustManager[] trustManagers = null; // null = cacerts padrão do JDK
            if (cfg.insecure()) {
                trustManagers = new TrustManager[] {new TrustAll()};
            } else if (cfg.trustStore() != null) {
                KeyStore ts = load(cfg.trustStore(), cfg.trustStorePassword());
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
                trustManagers = tmf.getTrustManagers();
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers, trustManagers, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao montar SSL do host: " + e.getMessage(), e);
        }
    }

    private static KeyStore load(String path, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(path)) {
            ks.load(in, chars(password));
        }
        return ks;
    }

    private static char[] chars(String s) {
        return s == null ? new char[0] : s.toCharArray();
    }

    /**
     * Aceita qualquer certificado e ignora o hostname. Só vale para hosts marcados com
     * {@code insecure: true} na config. É exceção explícita, nunca padrão.
     */
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) { }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) { }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) { }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
