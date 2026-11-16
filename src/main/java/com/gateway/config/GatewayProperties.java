package com.gateway.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração do gateway.
 *
 * <ul>
 *   <li>{@code hosts}: lista de hosts permitidos (allowlist). Host fora da lista = 403.
 *       Cada host pode ter opções de SSL.</li>
 *   <li>{@code apis}: nomes lógicos de API; o Oracle cita só o nome e o gateway injeta os headers
 *       (token etc.), então credenciais nunca passam pelo PL/SQL.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @DefaultValue Map<String, Host> hosts,
        @DefaultValue Map<String, Api> apis,
        @DefaultValue("10") int connectTimeoutSeconds,
        @DefaultValue("60") int readTimeoutSeconds,
        @DefaultValue("2") int retryMax,
        @DefaultValue("500") long retryBackoffMs,
        @DefaultValue("500") int maxItems,
        @DefaultValue("5000000") long maxResponseBytes,
        @DefaultValue("30") int cacheTtlSeconds,
        @DefaultValue("300") int cacheMaxTtlSeconds,
        @DefaultValue("5000") int cacheMaxEntries,
        @DefaultValue("100") int maxConcurrentPerHost,
        @DefaultValue("10") int queueTimeoutSeconds) {

    /** Opções por host. Tudo opcional; sem nada = validação TLS padrão do JDK. */
    public record Host(
            boolean insecure,
            String trustStore,
            String trustStorePassword,
            String keyStore,
            String keyStorePassword) {
    }

    /**
     * Configuração de uma API lógica. Se {@code host} for informado, os headers (token) só são
     * enviados para esse host; usar a API em outro host é recusado (evita vazar credencial).
     */
    public record Api(String host, @DefaultValue Map<String, String> headers) {
    }
}
