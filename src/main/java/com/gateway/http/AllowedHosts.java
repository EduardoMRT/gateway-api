package com.gateway.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lista de hosts permitidos (allowlist).
 *
 * <p>Vem de duas fontes somadas:
 * <ul>
 *   <li><b>fixos</b>: variável de ambiente / application.yml (valem até reiniciar);</li>
 *   <li><b>arquivo de texto</b> (opcional), um host por linha: reaproveitado automaticamente
 *       quando é editado, sem reiniciar o gateway. Linhas vazias e começadas por {@code #} são ignoradas.</li>
 * </ul>
 * Aceita colar uma URL inteira ({@code https://pncp.gov.br/api/x}): só o host é usado.
 */
public final class AllowedHosts {

    private static final Logger log = LoggerFactory.getLogger(AllowedHosts.class);

    private final Set<String> fixos = new TreeSet<>();
    private final Path arquivo;
    private final long intervaloMs;
    private final LongSupplier relogio;

    private volatile Set<String> doArquivo = Set.of();
    private volatile long ultimaChecagem = Long.MIN_VALUE / 2;
    private volatile long ultimaModificacao = -1;

    /** @param arquivo pode ser null (só fontes fixas) */
    public AllowedHosts(Collection<String> fixos, Path arquivo, long intervaloMs, LongSupplier relogio) {
        for (String h : fixos) {
            String n = normalizar(h);
            if (!n.isEmpty()) {
                this.fixos.add(n);
            }
        }
        this.arquivo = arquivo;
        this.intervaloMs = intervaloMs;
        this.relogio = relogio;
        atualizar();
    }

    public boolean permite(String host) {
        if (host == null) {
            return false;
        }
        String h = normalizar(host);
        if (fixos.contains(h)) {
            return true;
        }
        atualizar();
        return doArquivo.contains(h);
    }

    /** Todos os hosts liberados no momento (ordenados). */
    public Set<String> todos() {
        atualizar();
        Set<String> t = new TreeSet<>(fixos);
        t.addAll(doArquivo);
        return t;
    }

    private void atualizar() {
        if (arquivo == null) {
            return;
        }
        long agora = relogio.getAsLong();
        if (agora - ultimaChecagem < intervaloMs) {
            return;
        }
        synchronized (this) {
            if (agora - ultimaChecagem < intervaloMs) {
                return;
            }
            ultimaChecagem = agora;
            try {
                if (!Files.exists(arquivo)) {
                    if (!doArquivo.isEmpty()) {
                        log.info("Arquivo de hosts removido: lista do arquivo esvaziada");
                    }
                    doArquivo = Set.of();
                    ultimaModificacao = -1;
                    return;
                }
                long mod = Files.getLastModifiedTime(arquivo).toMillis();
                if (mod == ultimaModificacao) {
                    return;
                }
                Set<String> novo = ler(arquivo);
                doArquivo = novo;
                ultimaModificacao = mod;
                log.info("Hosts permitidos recarregados de {} ({} hosts)", arquivo, novo.size());
            } catch (IOException e) {
                log.warn("Falha ao ler o arquivo de hosts; mantendo a lista anterior: {}", e.toString());
            }
        }
    }

    private static Set<String> ler(Path arquivo) throws IOException {
        Set<String> out = new TreeSet<>();
        for (String linha : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
            int c = linha.indexOf('#');
            String limpa = (c >= 0 ? linha.substring(0, c) : linha).trim();
            if (limpa.isEmpty()) {
                continue;
            }
            for (String parte : limpa.split("[\\s,;]+")) {
                String n = normalizar(parte);
                if (!n.isEmpty()) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    /** minúsculas, sem esquema (http://), sem caminho e sem porta. */
    static String normalizar(String valor) {
        if (valor == null) {
            return "";
        }
        String v = valor.trim().toLowerCase();
        int esquema = v.indexOf("://");
        if (esquema >= 0) {
            v = v.substring(esquema + 3);
        }
        int fim = v.length();
        for (char sep : new char[] {'/', '?', '#', ':'}) {
            int i = v.indexOf(sep);
            if (i >= 0 && i < fim) {
                fim = i;
            }
        }
        return v.substring(0, fim);
    }
}
