package com.gateway.http;

import com.gateway.config.GatewayProperties;
import com.gateway.web.GatewayException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Faz a chamada HTTP(S) real para a API externa. */
@Component
public class ExternalApiClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiClient.class);
    private static final Set<String> METODOS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<Integer> RETRY_STATUS = Set.of(429, 502, 503, 504);

    private final GatewayProperties props;
    private final HttpClientProvider clients;

    public ExternalApiClient(GatewayProperties props, HttpClientProvider clients) {
        this.props = props;
        this.clients = clients;
    }

    public record ExternalResponse(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * @param api nome lógico da API (headers vêm da config); pode ser null
     */
    public ExternalResponse call(String metodo, String url, String body, String contentType, String api) {
        String method = metodo == null ? "GET" : metodo.toUpperCase();
        if (!METODOS.contains(method)) {
            throw new GatewayException(400, "Método não suportado: " + metodo);
        }
        URI uri = parse(url);
        String host = uri.getHost();
        if (host == null || !props.hosts().containsKey(host)) {
            throw new GatewayException(403, "Host não permitido: " + host);
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(props.readTimeoutSeconds()))
                .header("Accept", "application/json");
        if (api != null && !api.isBlank()) {
            GatewayProperties.Api cfg = props.apis().get(api);
            if (cfg == null) {
                throw new GatewayException(400, "API lógica desconhecida: " + api);
            }
            for (Map.Entry<String, String> h : cfg.headers().entrySet()) {
                rb.header(h.getKey(), h.getValue());
            }
        }
        boolean temCorpo = body != null && !body.isEmpty();
        if (temCorpo) {
            rb.header("Content-Type", contentType == null ? "application/json" : contentType);
            rb.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpRequest request = rb.build();

        // Retry só para GET: repetir POST pode duplicar o efeito na API externa.
        int tentativas = "GET".equals(method) ? 1 + props.retryMax() : 1;
        for (int i = 1; ; i++) {
            long t0 = System.nanoTime();
            try {
                HttpResponse<byte[]> resp = clients.forHost(host)
                        .send(request, HttpResponse.BodyHandlers.ofByteArray());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                log.info("{} {}{} -> {} ({} ms, tentativa {})", method, host, uri.getPath(), resp.statusCode(), ms, i);
                if (resp.body().length > props.maxResponseBytes()) {
                    throw new GatewayException(502, "Resposta da API externa excede o limite configurado");
                }
                if (RETRY_STATUS.contains(resp.statusCode()) && i < tentativas) {
                    pausa(i);
                    continue;
                }
                return new ExternalResponse(resp.statusCode(), new String(resp.body(), StandardCharsets.UTF_8));
            } catch (HttpTimeoutException e) {
                log.warn("{} {}{} timeout (tentativa {})", method, host, uri.getPath(), i);
                if (i >= tentativas) {
                    throw new GatewayException(504, "Timeout ao chamar " + host);
                }
                pausa(i);
            } catch (IOException e) {
                log.warn("{} {}{} falhou: {} (tentativa {})", method, host, uri.getPath(), e.toString(), i);
                if (i >= tentativas) {
                    throw new GatewayException(502, "Falha ao chamar " + host + ": " + e.getMessage());
                }
                pausa(i);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GatewayException(502, "Chamada interrompida");
            }
        }
    }

    private static URI parse(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new GatewayException(400, "URL deve ser http ou https");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new GatewayException(400, "URL inválida");
        }
    }

    private void pausa(int tentativa) {
        try {
            Thread.sleep(props.retryBackoffMs() * tentativa);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
