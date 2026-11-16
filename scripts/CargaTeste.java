import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Teste de carga simples para o gateway (ou qualquer URL).
 *
 * Uso (Java 21, sem dependências):
 *   java scripts/CargaTeste.java "<url>" <concorrencia> <total>
 *
 * Exemplo, 50 "cidades" ao mesmo tempo, 5000 chamadas:
 *   java scripts/CargaTeste.java "http://localhost:8080/extrair?url=...&campo=x" 50 5000
 *
 * Dica: para simular cidades diferentes consultando coisas diferentes, coloque {n} na URL;
 * ele é trocado por um número de 0 a 99 a cada chamada:
 *   "http://localhost:8080/extrair?url=https://pncp.gov.br/...&onde=numero={n}&campo=x"
 */
public class CargaTeste {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Uso: java CargaTeste.java \"<url>\" <concorrencia> <total>");
            System.exit(2);
        }
        String modelo = args[0];
        int concorrencia = Integer.parseInt(args[1]);
        int total = Integer.parseInt(args[2]);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        Semaphore limite = new Semaphore(concorrencia);
        long[] latenciasMs = new long[total];
        Map<Integer, Integer> status = new TreeMap<>();

        long inicio = System.nanoTime();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                final int n = i;
                limite.acquire();
                fs.add(ex.submit(() -> {
                    try {
                        String url = modelo.replace("{n}", String.valueOf(n % 100));
                        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(120)).GET().build();
                        long t0 = System.nanoTime();
                        int st;
                        try {
                            st = client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                        } catch (Exception e) {
                            st = -1;
                        }
                        latenciasMs[n] = (System.nanoTime() - t0) / 1_000_000;
                        synchronized (status) {
                            status.merge(st, 1, Integer::sum);
                        }
                    } finally {
                        limite.release();
                    }
                }));
            }
            for (Future<?> f : fs) {
                f.get();
            }
        }
        double seg = (System.nanoTime() - inicio) / 1e9;

        Arrays.sort(latenciasMs);
        System.out.printf("Chamadas: %d | concorrência: %d | tempo total: %.2f s | vazão: %.0f req/s%n",
                total, concorrencia, seg, total / seg);
        System.out.printf("Latência ms: p50=%d  p90=%d  p95=%d  p99=%d  max=%d%n",
                p(latenciasMs, 50), p(latenciasMs, 90), p(latenciasMs, 95), p(latenciasMs, 99),
                latenciasMs[total - 1]);
        System.out.println("Status: " + status + "   (-1 = erro de conexão/timeout)");
    }

    private static long p(long[] ordenado, int pct) {
        int i = (int) Math.ceil(pct / 100.0 * ordenado.length) - 1;
        return ordenado[Math.max(0, Math.min(i, ordenado.length - 1))];
    }
}
