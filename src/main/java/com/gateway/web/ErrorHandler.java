package com.gateway.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Erros sempre em texto simples, fáceis de ler no PL/SQL. */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<String> gateway(GatewayException e) {
        return GatewayController.resposta(e.status(), e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> parametro(MissingServletRequestParameterException e) {
        return GatewayController.resposta(400, "Parâmetro obrigatório ausente: " + e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> inesperado(Exception e) {
        log.error("Erro inesperado", e);
        return GatewayController.resposta(500, "Erro interno do gateway");
    }
}
