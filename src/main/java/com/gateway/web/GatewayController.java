package com.gateway.web;

import com.gateway.http.ExternalApiClient;
import com.gateway.http.ExternalApiClient.ExternalResponse;
import com.gateway.json.JsonExtractor;
import com.gateway.json.JsonExtractor.Modo;
import com.gateway.config.GatewayProperties;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints para o Oracle 9i. Sempre text/plain UTF-8.
 *
 * <p>Respostas: 200 = valor(es); 404 = nada encontrado (corpo vazio, vira NULL no PL/SQL);
 * 400 parâmetro inválido; 403 host fora da allowlist; 502 API externa falhou; 504 timeout.
 */
@RestController
public class GatewayController {

    private static final MediaType TEXTO = new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8);

    private final ExternalApiClient client;
    private final JsonExtractor extractor;
    private final GatewayProperties props;

    public GatewayController(ExternalApiClient client, JsonExtractor extractor, GatewayProperties props) {
        this.client = client;
        this.extractor = extractor;
        this.props = props;
    }

    @GetMapping("/saude")
    public ResponseEntity<String> saude() {
        return resposta(200, "OK");
    }

    /** GET na API externa; devolve campo(s) do(s) item(ns) que batem com o filtro. */
    @GetMapping("/extrair")
    public ResponseEntity<String> extrair(
            @RequestParam String url,
            @RequestParam String campo,
            @RequestParam(required = false) String lista,
            @RequestParam(required = false) String onde,
            @RequestParam(defaultValue = "primeiro") String modo,
            @RequestParam(defaultValue = "|") String sep,
            @RequestParam(required = false) Integer max,
            @RequestParam(required = false) String api,
            @RequestParam(required = false) Integer cache) {
        ExternalResponse r = client.call("GET", url, null, null, api, cache);
        return tratar(r, lista, onde, campo, modo, sep, max);
    }

    /** Hosts que o gateway aceita chamar neste momento (um por linha). */
    @GetMapping("/hosts")
    public ResponseEntity<String> hosts() {
        return resposta(200, String.join("\n", client.hostsPermitidos()) + "\n");
    }

    /** Contadores simples (chave=valor por linha) para monitorar carga, cache e saturação. */
    @GetMapping("/estatisticas")
    public ResponseEntity<String> estatisticas() {
        StringBuilder sb = new StringBuilder();
        client.estatisticas().forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return resposta(200, sb.toString());
    }

    /** Envia (POST por padrão) o corpo recebido para a API externa. Sem {@code campo}, devolve o corpo bruto. */
    @PostMapping("/enviar")
    public ResponseEntity<String> enviar(
            @RequestParam String url,
            @RequestParam(defaultValue = "POST") String metodo,
            @RequestParam(required = false) String campo,
            @RequestParam(required = false) String lista,
            @RequestParam(required = false) String onde,
            @RequestParam(defaultValue = "primeiro") String modo,
            @RequestParam(defaultValue = "|") String sep,
            @RequestParam(required = false) Integer max,
            @RequestParam(required = false) String api,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestBody(required = false) String corpo) {
        ExternalResponse r = client.call(metodo, url, corpo, contentType, api, null);
        if (campo == null || campo.isBlank()) {
            if (!r.ok()) {
                throw erroExterno(r);
            }
            return resposta(200, r.body());
        }
        return tratar(r, lista, onde, campo, modo, sep, max);
    }

    private ResponseEntity<String> tratar(ExternalResponse r, String lista, String onde, String campo,
                                          String modo, String sep, Integer max) {
        if (r.status() == 404) {
            return resposta(404, "");
        }
        if (!r.ok()) {
            throw erroExterno(r);
        }
        if (sep.length() != 1) {
            throw new GatewayException(400, "sep deve ter 1 caractere");
        }
        Modo m;
        try {
            m = Modo.valueOf(modo.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new GatewayException(400, "modo deve ser primeiro ou todos");
        }
        int limite = Math.min(max == null ? props.maxItems() : max, props.maxItems());
        Optional<String> texto = extractor.extrair(r.body(), lista, onde, campo, m, sep, limite);
        return texto.map(t -> resposta(200, t)).orElseGet(() -> resposta(404, ""));
    }

    private static GatewayException erroExterno(ExternalResponse r) {
        String trecho = r.body() == null ? "" : r.body().substring(0, Math.min(300, r.body().length()));
        return new GatewayException(502, "API externa respondeu HTTP " + r.status() + ": " + trecho);
    }

    static ResponseEntity<String> resposta(int status, String corpo) {
        return ResponseEntity.status(status).contentType(TEXTO).body(corpo);
    }
}
