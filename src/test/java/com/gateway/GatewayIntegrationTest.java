package com.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Sobe um "API externa" falsa e chama o gateway de verdade, como o Oracle faria. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gateway.hosts[localhost]=",
            "gateway.apis[teste].headers[X-Token]=segredo",
            "gateway.retry-backoff-ms=10"
        })
class GatewayIntegrationTest {

    static HttpServer externo;
    static String base;
    static final AtomicInteger chamadasFlaky = new AtomicInteger();
    static final AtomicInteger chamadasPostFalho = new AtomicInteger();
    static final AtomicReference<String> corpoRecebido = new AtomicReference<>();

    @Autowired TestRestTemplate rest;

    @BeforeAll
    static void sobeExterno() throws IOException {
        externo = HttpServer.create(new InetSocketAddress("localhost", 0), 0);

        // Lista com objetos aninhados e "}" dentro de string: o caso que quebrava o INSTR.
        externo.createContext("/empenhos", ex -> json(ex, 200, """
            [
              {"numeroEmpenho":"122-1","sequencialEmpenho":41,"obs":"texto com } e { dentro",
               "itens":[{"numeroEmpenho":"999-9","sequencialEmpenho":7}]},
              {"numeroEmpenho":"123-1","sequencialEmpenho":42,"descricao":"ok | com pipe",
               "itens":[{"numeroEmpenho":"123-1","sequencialEmpenho":8}]},
              {"numeroEmpenho":"123-1","sequencialEmpenho":43,"descricao":"segundo"}
            ]"""));

        externo.createContext("/embrulhado", ex -> json(ex, 200,
                "{\"data\":[{\"id\":1,\"nome\":\"Ana\"},{\"id\":2,\"nome\":\"Bia\"}]}"));

        externo.createContext("/auth", ex -> json(ex, 200,
                "{\"token\":\"" + ex.getRequestHeaders().getFirst("X-Token") + "\"}"));

        externo.createContext("/flaky", ex -> {
            if (chamadasFlaky.incrementAndGet() < 3) {
                json(ex, 503, "{\"erro\":\"indisponivel\"}");
            } else {
                json(ex, 200, "{\"ok\":\"voltou\"}");
            }
        });

        externo.createContext("/docs", ex -> {
            corpoRecebido.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(ex, 201, "{\"protocolo\":\"P-123\"}");
        });

        externo.createContext("/docs-falha", ex -> {
            chamadasPostFalho.incrementAndGet();
            json(ex, 503, "{\"erro\":\"fora\"}");
        });

        externo.start();
        base = "http://localhost:" + externo.getAddress().getPort();
    }

    @AfterAll
    static void paraExterno() {
        externo.stop(0);
    }

    private static void json(com.sun.net.httpserver.HttpExchange ex, int status, String corpo) throws IOException {
        byte[] b = corpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    void extraiSequencialIgnorandoObjetosAninhados() {
        ResponseEntity<String> r = rest.getForEntity(
                "/extrair?url=" + base + "/empenhos&onde=numeroEmpenho=123-1&campo=sequencialEmpenho", String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isEqualTo("42"); // não confunde com o 8 do sub-objeto
    }

    @Test
    void naoEncontradoDevolve404Vazio() {
        ResponseEntity<String> r = rest.getForEntity(
                "/extrair?url=" + base + "/empenhos&onde=numeroEmpenho=000&campo=sequencialEmpenho", String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(r.getBody()).isEmpty();
    }

    @Test
    void modoTodosDevolveUmaLinhaPorItemComVariosCampos() {
        ResponseEntity<String> r = rest.getForEntity(
                "/extrair?url=" + base + "/empenhos&onde=numeroEmpenho=123-1"
                        + "&campo=numeroEmpenho,sequencialEmpenho,descricao&modo=todos", String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        // pipe dentro do valor vira espaço, para não quebrar o formato
        assertThat(r.getBody()).isEqualTo("123-1|42|ok   com pipe\n123-1|43|segundo");
    }

    @Test
    void listaEmbrulhadaPorCaminho() {
        ResponseEntity<String> r = rest.getForEntity(
                "/extrair?url=" + base + "/embrulhado&lista=data&onde=id=2&campo=nome", String.class);
        assertThat(r.getBody()).isEqualTo("Bia");
    }

    @Test
    void hostForaDaAllowlistEBloqueado() {
        ResponseEntity<String> r = rest.getForEntity(
                "/extrair?url=" + base.replace("localhost", "127.0.0.1") + "/empenhos&campo=x", String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void injetaHeadersDaApiLogica() {
        ResponseEntity<String> r = rest.getForEntity(
                "/extrair?url=" + base + "/auth&campo=token&api=teste", String.class);
        assertThat(r.getBody()).isEqualTo("segredo");
    }

    @Test
    void getFazRetryEmErroTransitorio() {
        chamadasFlaky.set(0);
        ResponseEntity<String> r = rest.getForEntity(
                "/extrair?url=" + base + "/flaky&campo=ok", String.class);
        assertThat(r.getBody()).isEqualTo("voltou");
        assertThat(chamadasFlaky.get()).isEqualTo(3);
    }

    @Test
    void postEnviaCorpoEDevolveCampoDaResposta() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.postForEntity(
                "/enviar?url=" + base + "/docs&campo=protocolo",
                new HttpEntity<>("{\"numero\":\"123\",\"nome\":\"João\"}", h), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isEqualTo("P-123");
        assertThat(corpoRecebido.get()).isEqualTo("{\"numero\":\"123\",\"nome\":\"João\"}");
    }

    @Test
    void postNaoFazRetryEDevolve502() {
        chamadasPostFalho.set(0);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.postForEntity(
                "/enviar?url=" + base + "/docs-falha", new HttpEntity<>("{}", h), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(502);
        assertThat(chamadasPostFalho.get()).isEqualTo(1); // POST nunca repete
    }

    @Test
    void parametroObrigatorioAusenteDevolve400() {
        ResponseEntity<String> r = rest.getForEntity("/extrair?url=" + base + "/empenhos", String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }
}
