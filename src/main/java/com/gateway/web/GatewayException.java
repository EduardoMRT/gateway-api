package com.gateway.web;

/** Erro com status HTTP que será devolvido ao Oracle como texto simples. */
public class GatewayException extends RuntimeException {
    private final int status;

    public GatewayException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
