package com.gateway.http;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Cache com TTL curto + "single-flight": se N chamadas idênticas chegam ao mesmo tempo,
 * só UMA vai à API externa e as outras esperam e reaproveitam a resposta.
 *
 * <p>É isso que segura a carga quando várias cidades consultam o mesmo dado ao mesmo tempo.
 * Sem dependências externas; limitado em tamanho; seguro para threads (inclusive virtuais).
 */
public final class SingleFlightCache<V> {

    private record Entry<V>(V value, long expiraEm) { }

    private final ConcurrentHashMap<String, Entry<V>> prontos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<V>> emVoo = new ConcurrentHashMap<>();
    private final int maxEntradas;
    private final java.util.function.LongSupplier relogio;

    private final AtomicLong acertos = new AtomicLong();
    private final AtomicLong juntadas = new AtomicLong();
    private final AtomicLong cargas = new AtomicLong();

    public SingleFlightCache(int maxEntradas, java.util.function.LongSupplier relogio) {
        this.maxEntradas = Math.max(1, maxEntradas);
        this.relogio = relogio;
    }

    /**
     * @param ttlMs      tempo de vida do resultado; 0 = não guarda (mas ainda junta chamadas simultâneas)
     * @param carregador chamada real à API externa
     * @param guardavel  decide se o resultado entra no cache (ex.: só respostas 2xx)
     */
    public V get(String chave, long ttlMs, Supplier<V> carregador, Predicate<V> guardavel) {
        long agora = relogio.getAsLong();
        if (ttlMs > 0) {
            Entry<V> e = prontos.get(chave);
            if (e != null) {
                if (e.expiraEm() > agora) {
                    acertos.incrementAndGet();
                    return e.value();
                }
                prontos.remove(chave, e);
            }
        }

        CompletableFuture<V> meu = new CompletableFuture<>();
        CompletableFuture<V> existente = emVoo.putIfAbsent(chave, meu);
        if (existente != null) {
            juntadas.incrementAndGet();
            try {
                return existente.join();
            } catch (CompletionException ex) {
                if (ex.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw ex;
            }
        }

        try {
            V valor = carregador.get();
            cargas.incrementAndGet();
            if (ttlMs > 0 && guardavel.test(valor)) {
                abrirEspaco(agora);
                prontos.put(chave, new Entry<>(valor, relogio.getAsLong() + ttlMs));
            }
            meu.complete(valor);
            return valor;
        } catch (RuntimeException ex) {
            meu.completeExceptionally(ex);
            throw ex;
        } finally {
            emVoo.remove(chave, meu);
        }
    }

    /** Mantém o tamanho limitado: remove expirados e, se ainda estiver cheio, ~10% das entradas. */
    private void abrirEspaco(long agora) {
        if (prontos.size() < maxEntradas) {
            return;
        }
        prontos.values().removeIf(e -> e.expiraEm() <= agora);
        if (prontos.size() < maxEntradas) {
            return;
        }
        int remover = Math.max(1, maxEntradas / 10);
        for (Iterator<String> it = prontos.keySet().iterator(); it.hasNext() && remover-- > 0; ) {
            it.next();
            it.remove();
        }
    }

    public int tamanho() { return prontos.size(); }
    public long acertos() { return acertos.get(); }
    public long juntadas() { return juntadas.get(); }
    public long cargas() { return cargas.get(); }
}
