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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Faz a chamada HTTP(S) real para a API externa.
 *
 * <p>Pensado para muita concorrência (várias cidades ao mesmo tempo):
 * <ul>
 *   <li>GET passa por cache de TTL curto + junção de chamadas idênticas simultâneas;</li>
 *   <li>limite de chamadas simultâneas por host (protege a API externa e o próprio gateway);</li>
 *   <li>conexões reaproveitadas (um HttpClient por host);</li>
 *   <li>roda em threads virtuais (ver application.yml), então esperar I/O é barato.</li>
 * </ul>
 */
@Component
public class ExternalApiClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiClient.class);
    private static final Set<String> METODOS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<Integer> RETRY_STATUS = Set.of(429, 502, 503, 504);

    private final GatewayProperties props;
    private final HttpClientProvider clients;
    private final SingleFlightCache<ExternalResponse> cache;
    private final Map<String, Semaphore> limites = new ConcurrentHashMap<>();

    private final AtomicLong chamadasExternas = new AtomicLong();
    private final AtomicLong tentativasExtras = new AtomicLong();
    private final AtomicLong saturados = new AtomicLong();
    private final AtomicLong falhas = new AtomicLong();

    public ExternalApiClient(GatewayProperties props, HttpClientProvider clients) {
        this.props = props;
        this.clients = clients;
        this.cache = new SingleFlightCache<>(props.cacheMaxEntries(), System::currentTimeMillis);
    }

    public record ExternalResponse(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * @param api            nome lógico da API (headers vêm da config); pode ser null
     * @param cacheSegundos  TTL do cache só para GET; null = padrão da config; 0 = não usar cache
     */
    public ExternalResponse call(String metodo, String url, String body, String contentType,
                                 String api, Integer cacheSegundos) {
        String method = metodo == null ? "GET" : metodo.toUpperCase();
        if (!METODOS.contains(method)) {
            throw new GatewayException(400, "Método não suportado: " + metodo);
        }
        URI uri = parse(url);
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase();
        if (host == null || !props.hosts().containsKey(host)) {
            throw new GatewayException(403, "Host não permitido: " + host);
        }
        Map<String, String> headers = headersDaApi(api, host);
        boolean temCorpo = body != null && !body.isEmpty();

        if ("GET".equals(method) && !temCorpo) {
            long ttlMs = ttlSegundos(cacheSegundos) * 1000L;
            // A chave inclui o nome lógico da API: credenciais diferentes nunca compartilham cache.
            String chave = (api == null ? "" : api) + "|" + url;
            return cache.get(chave, ttlMs,
                    () -> executar(method, uri, host, null, null, headers),
                    ExternalResponse::ok);
        }
        return executar(method, uri, host, body, contentType, headers);
    }

    private long ttlSegundos(Integer pedido) {
        long ttl = pedido == null ? props.cacheTtlSeconds() : pedido;
        return Math.max(0, Math.min(ttl, props.cacheMaxTtlSeconds()));
    }

    private Map<String, String> headersDaApi(String api, String host) {
        if (api == null || api.isBlank()) {
            return Map.of();
        }
        GatewayProperties.Api cfg = props.apis().get(api);
        if (cfg == null) {
            throw new GatewayException(400, "API lógica desconhecida: " + api);
        }
        if (cfg.host() != null && !cfg.host().equalsIgnoreCase(host)) {
            throw new GatewayException(403, "A API lógica '" + api + "' não pode ser usada com o host " + host);
        }
        return cfg.headers();
    }

    private ExternalResponse executar(String method, URI uri, String host, String body,
                                      String contentType, Map<String, String> headers) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(props.readTimeoutSeconds()))
                .header("Accept", "application/json");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            rb.header(h.getKey(), h.getValue());
        }
        if (body != null && !body.isEmpty()) {
            rb.header("Content-Type", contentType == null ? "application/json" : contentType);
            rb.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpRequest request = rb.build();

        // Retry só para GET: repetir POST pode duplicar o efeito na API externa.
        int tentativas = "GET".equals(method) ? 1 + props.retryMax() : 1;
        Semaphore limite = limites.computeIfAbsent(host, h -> new Semaphore(props.maxConcurrentPerHost()));

        for (int i = 1; ; i++) {
            long t0 = System.nanoTime();
            try {
                if (!limite.tryAcquire(props.queueTimeoutSeconds(), TimeUnit.SECONDS)) {
                    saturados.incrementAndGet();
                    throw new GatewayException(503, "Gateway saturado para " + host + "; tente novamente");
                }
                HttpResponse<byte[]> resp;
                try {
                    chamadasExternas.incrementAndGet();
                    resp = clients.forHost(host).send(request, HttpResponse.BodyHandlers.ofByteArray());
                } finally {
                    limite.release(); // libera antes de qualquer pausa de retry
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                log.info("{} {}{} -> {} ({} ms, tentativa {})", method, host, uri.getPath(), resp.statusCode(), ms, i);
                if (resp.body().length > props.maxResponseBytes()) {
                    throw new GatewayException(502, "Resposta da API externa excede o limite configurado");
                }
                if (RETRY_STATUS.contains(resp.statusCode()) && i < tentativas) {
                    tentativasExtras.incrementAndGet();
                    pausa(i);
                    continue;
                }
                return new ExternalResponse(resp.statusCode(), new String(resp.body(), StandardCharsets.UTF_8));
            } catch (HttpTimeoutException e) {
                log.warn("{} {}{} timeout (tentativa {})", method, host, uri.getPath(), i);
                if (i >= tentativas) {
                    falhas.incrementAndGet();
                    throw new GatewayException(504, "Timeout ao chamar " + host);
                }
                tentativasExtras.incrementAndGet();
                pausa(i);
            } catch (IOException e) {
                log.warn("{} {}{} falhou: {} (tentativa {})", method, host, uri.getPath(), e.toString(), i);
                if (i >= tentativas) {
                    falhas.incrementAndGet();
                    throw new GatewayException(502, "Falha ao chamar " + host + ": " + e.getMessage());
                }
                tentativasExtras.incrementAndGet();
                pausa(i);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GatewayException(502, "Chamada interrompida");
            }
        }
    }

    /** Contadores para o endpoint /estatisticas. */
    public Map<String, Long> estatisticas() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("chamadas_externas", chamadasExternas.get());
        m.put("tentativas_extras", tentativasExtras.get());
        m.put("falhas_externas", falhas.get());
        m.put("saturados_503", saturados.get());
        m.put("cache_acertos", cache.acertos());
        m.put("cache_chamadas_juntadas", cache.juntadas());
        m.put("cache_cargas", cache.cargas());
        m.put("cache_tamanho", (long) cache.tamanho());
        return m;
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
