package com.gateway.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.web.GatewayException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Extrai valores de um JSON: acha a lista, filtra os itens e devolve campos como texto.
 *
 * <p>Caminhos usam ponto ({@code data.itens}); segmento numérico indexa array ({@code itens.0.id}).
 * Filtro {@code onde}: {@code campo=valor;outro=valor} (todas as condições precisam bater).
 */
@Component
public class JsonExtractor {

    private final ObjectMapper mapper = new ObjectMapper();

    public enum Modo { PRIMEIRO, TODOS }

    /** @return texto (campos separados por {@code sep}, uma linha por item) ou vazio se nada bateu */
    public Optional<String> extrair(String json, String lista, String onde, String campos,
                                    Modo modo, String sep, int max) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new GatewayException(502, "A API externa não devolveu JSON válido");
        }
        if (root == null || root.isMissingNode()) {
            return Optional.empty();
        }

        List<JsonNode> itens = itens(navegar(root, lista));
        List<String[]> condicoes = condicoes(onde);
        List<String> paths = List.of(campos.split(",")).stream().map(String::trim).collect(Collectors.toList());

        List<String> linhas = new ArrayList<>();
        for (JsonNode item : itens) {
            if (!bate(item, condicoes)) {
                continue;
            }
            linhas.add(paths.stream().map(p -> texto(navegar(item, p), sep)).collect(Collectors.joining(sep)));
            if (modo == Modo.PRIMEIRO || linhas.size() >= max) {
                break;
            }
        }
        if (linhas.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join("\n", linhas));
    }

    private static JsonNode navegar(JsonNode no, String caminho) {
        if (caminho == null || caminho.isBlank()) {
            return no;
        }
        JsonNode atual = no;
        for (String seg : caminho.trim().split("\\.")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (atual.isArray() && seg.chars().allMatch(Character::isDigit)) {
                atual = atual.path(Integer.parseInt(seg));
            } else {
                atual = atual.path(seg);
            }
        }
        return atual;
    }

    private static List<JsonNode> itens(JsonNode no) {
        List<JsonNode> out = new ArrayList<>();
        if (no.isArray()) {
            no.forEach(out::add);
        } else if (!no.isMissingNode() && !no.isNull()) {
            out.add(no);
        }
        return out;
    }

    private static List<String[]> condicoes(String onde) {
        List<String[]> out = new ArrayList<>();
        if (onde == null || onde.isBlank()) {
            return out;
        }
        for (String c : onde.split(";")) {
            int i = c.indexOf('=');
            if (i <= 0) {
                throw new GatewayException(400, "Filtro inválido (use campo=valor): " + c);
            }
            out.add(new String[] {c.substring(0, i).trim(), c.substring(i + 1).trim()});
        }
        return out;
    }

    private static boolean bate(JsonNode item, List<String[]> condicoes) {
        for (String[] c : condicoes) {
            JsonNode v = navegar(item, c[0]);
            if (v.isMissingNode() || v.isNull() || !v.asText().trim().equals(c[1])) {
                return false;
            }
        }
        return true;
    }

    /** Valor como texto; separadores e quebras de linha dentro do valor viram espaço. */
    private static String texto(JsonNode no, String sep) {
        if (no.isMissingNode() || no.isNull()) {
            return "";
        }
        String s = no.isValueNode() ? no.asText() : no.toString();
        return s.replace(sep, " ").replace('\r', ' ').replace('\n', ' ');
    }
}
