# Manual de uso: gateway-api

Para quem programa em **Oracle PL/SQL (9i)** e precisa consumir APIs externas (PNCP etc.) sem
escrever HTTP e parse de JSON na mão.

---

## 1. Ideia em 30 segundos

```
Seu PL/SQL  --HTTP simples-->  gateway-api  --HTTPS/JSON-->  API externa
```

Você chama **uma função** do package `PKG_GATEWAY`, diz a URL da API, o filtro e o campo que
quer. O gateway faz a chamada, acha o item certo no JSON e devolve **só o valor**, em texto.

```sql
v_seq := TO_NUMBER(PKG_GATEWAY.EXTRAIR(
             p_url   => V_URL,
             p_onde  => 'numeroEmpenho=123-1',
             p_campo => 'sequencialEmpenho'));
```

Você **não** precisa mais de `UTL_HTTP`, `INSTR`/`SUBSTR` em JSON, tratamento de status HTTP,
nem de se preocupar com TLS.

---

## 2. Instalação

### 2.1 Gateway (uma vez, por quem cuida da infraestrutura)

```bash
mvn package
java -jar target/gateway-api-0.1.0.jar        # ou use o Dockerfile
curl http://servidor-gateway:8080/saude         # deve responder OK
```

Requisitos: Java 21. Deixe o gateway acessível **só pela rede interna** (firewall).

### 2.2 Lado Oracle (uma vez por banco)

```sql
@oracle/01_tipos.sql
@oracle/02_pkg_gateway.sql
```

Antes, abra `02_pkg_gateway.sql` e ajuste a constante com o endereço do gateway:

```sql
C_BASE CONSTANT VARCHAR2(200) := 'http://servidor-gateway:8080';
```

O usuário do banco precisa de permissão de rede para chamar esse endereço (`UTL_HTTP`).

---

## 3. Configurar o gateway (`application.yml`)

### 3.1 Liberar uma API externa (obrigatório)

O gateway só chama hosts da lista. Host fora da lista = erro 403.

```yaml
gateway:
  hosts:
    "[pncp.gov.br]": {}
    "[api.outra.gov.br]": {}
```

(O nome com ponto precisa dos colchetes e das aspas, como acima.)

### 3.2 API que exige token (opcional)

Coloque o token **no gateway**, não no PL/SQL:

```yaml
gateway:
  apis:
    exemplo:
      headers:
        Authorization: "Bearer ${EXEMPLO_TOKEN}"
```

No PL/SQL você só cita o nome: `p_api => 'exemplo'`.

### 3.3 Certificados (opcional)

```yaml
gateway:
  hosts:
    "[api.interna]":
      insecure: true            # aceita qualquer certificado: SÓ homologação
    "[api.com.certificado]":
      trust-store: /etc/gateway/trust.p12     # certificado próprio da API
      trust-store-password: ${TRUST_PASS}
      key-store: /etc/gateway/cliente.p12     # seu certificado (mTLS)
      key-store-password: ${KEY_PASS}
```

Sem nada disso, vale a validação normal de certificados.

---

## 4. Receitas de uso

### 4.1 Um valor

```sql
v_seq := PKG_GATEWAY.EXTRAIR(
             p_url   => 'https://pncp.gov.br/api/...',
             p_onde  => 'numeroEmpenho=123-1',
             p_campo => 'sequencialEmpenho');
```

- Achou: devolve o valor (texto). Converta com `TO_NUMBER` se for número.
- **Não achou: devolve `NULL`** (não é erro).

### 4.2 Mais de um filtro

Separe com `;`: todos precisam bater.

```sql
p_onde => 'ano=2026;tipo=O'
```

### 4.3 JSON com a lista dentro de outro campo

Se a resposta é `{"data":[ {...}, {...} ]}`, diga onde está a lista:

```sql
p_lista => 'data'          -- ou 'resultado.itens' para mais níveis
```

### 4.4 Campo dentro de outro objeto

Use ponto; número = posição no array (começa em 0):

```sql
p_campo => 'fornecedor.razaoSocial'
p_campo => 'itens.0.valor'
```

### 4.5 Vários itens, usados em SQL

```sql
SELECT c1 AS numero, c2 AS sequencial, c3 AS descricao
FROM   TABLE(CAST(PKG_GATEWAY.LISTAR(
           p_url    => 'https://pncp.gov.br/api/...',
           p_lista  => 'data',
           p_campos => 'numeroEmpenho,sequencialEmpenho,descricao',
           p_max    => 100) AS T_GW_TAB));
```

Os campos saem em `c1`, `c2`... na ordem de `p_campos` (até 10). Você pode juntar com tabelas do
sistema: `... JOIN a_empenho e ON e.nro = t.c1`.

**Prefira `LISTAR` a chamar `EXTRAIR` dentro de um loop**: uma chamada traz tudo, em vez de N.

### 4.6 Enviar dados (POST)

```sql
v_protocolo := PKG_GATEWAY.ENVIAR(
                   p_url   => 'https://api.exemplo.gov.br/documentos',
                   p_corpo => '{"numero":"123","valor":150.00}',
                   p_campo => 'protocolo',      -- campo da resposta; NULL = resposta inteira
                   p_api   => 'exemplo');
```

`p_metodo => 'PUT'` (ou `PATCH`, `DELETE`) para outros métodos.

> **O POST nunca é repetido automaticamente**, para não duplicar um envio. Se der erro, **não**
> reenvie sem antes conferir na API se o primeiro chegou.

### 4.7 Tratando erros

```sql
BEGIN
    v_seq := PKG_GATEWAY.EXTRAIR(...);
    IF v_seq IS NULL THEN
        NULL;  -- não encontrou: trate como "sem dado"
    END IF;
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE = -20100 THEN
            -- problema na chamada; SQLERRM traz a mensagem do gateway
            RAISE;
        END IF;
END;
```

---

## 5. Referência rápida

| Parâmetro | Função | Descrição |
|---|---|---|
| `p_url` | todas | URL completa da API externa |
| `p_lista` | EXTRAIR, LISTAR | Caminho do array no JSON. Vazio = a raiz |
| `p_onde` | EXTRAIR, LISTAR | Filtro `campo=valor`; vários separados por `;` |
| `p_campo` / `p_campos` | EXTRAIR / LISTAR | Campo(s) a devolver (LISTAR: até 10, separados por vírgula) |
| `p_max` | LISTAR | Máximo de linhas |
| `p_api` | todas | Nome lógico da API (token/headers vêm do gateway) |
| `p_cache` | EXTRAIR, LISTAR | Segundos de cache. Vazio = padrão do gateway; `0` = sempre consultar a API |
| `p_corpo`, `p_metodo` | ENVIAR | Corpo JSON e método (`POST` padrão) |

Contrato completo (formatos, códigos HTTP): [`CONTRATO.md`](CONTRATO.md).

---

## 6. Erros comuns

Todos chegam como **ORA-20100** com a mensagem do gateway.

| Mensagem | Causa | O que fazer |
|---|---|---|
| `Gateway HTTP 403: Host não permitido: ...` | O host da API não está em `gateway.hosts` | Pedir ao responsável pelo gateway para liberar o host |
| `Gateway HTTP 400: ... API lógica desconhecida` | `p_api` com nome que não existe na config | Conferir o nome em `gateway.apis` |
| `Gateway HTTP 400: Filtro inválido` | `p_onde` sem `=` | Usar `campo=valor` |
| `Gateway HTTP 502: API externa respondeu HTTP 401/500...` | A API externa recusou ou falhou | Ler o trecho da mensagem; checar token/URL |
| `Gateway HTTP 502: ... não devolveu JSON válido` | A URL não devolve JSON (HTML de erro, por exemplo) | Conferir a URL no navegador |
| `Gateway HTTP 503: Gateway saturado` | Muitas chamadas simultâneas para o mesmo host | Tentar de novo em instantes; avisar a infraestrutura se for frequente |
| `Gateway HTTP 504: Timeout` | A API externa demorou demais | Tentar de novo; a API pode estar fora do ar |
| `Resposta do gateway excede 32767` | Resultado grande demais para `VARCHAR2` (9i) | Usar `p_max` ou filtrar mais |
| `ORA-29273 / ORA-12535` etc. | O Oracle não alcança o gateway | Conferir `C_BASE`, rede, permissão de `UTL_HTTP` |

Resultado vazio sem erro = **não encontrou** (NULL ou zero linhas).

---

## 7. Desempenho (muitas cidades ao mesmo tempo)

O gateway foi pensado para vários clientes simultâneos:

- **Cache curto (padrão 30 s):** consultas GET idênticas dentro desse prazo nem chegam à API
  externa. Isso protege a API externa e deixa a resposta quase instantânea.
- **Junção de pedidos simultâneos:** se 50 cidades pedem **a mesma coisa ao mesmo tempo**, só
  **uma** chamada vai à API externa e as outras reaproveitam a resposta.
- **Limite por host (padrão 100 simultâneas):** evita derrubar a API externa. Acima disso os
  pedidos esperam até 10 s e, se não houver vaga, recebem 503.
- **Threads virtuais e conexões reaproveitadas:** esperar a API externa quase não consome recursos.

### O que você controla no PL/SQL

- Dado que **muda pouco** (cadastros, empenhos já emitidos): deixe o cache padrão ou aumente
  (`p_cache => 120`).
- Dado que **precisa estar fresco** (status que acabou de mudar): `p_cache => 0`.
- Evite chamar a API dentro de loops linha a linha. Use `LISTAR` e faça o JOIN em SQL.
- Use filtro (`p_onde`) em vez de trazer tudo e filtrar depois.

> **Atenção com credenciais diferentes por cidade.** O cache é compartilhado entre quem usa o
> **mesmo `p_api` e a mesma URL**. Se cada cidade tem seu próprio token, **cadastre um `api`
> separado por cidade** (`pncp_cidadeA`, `pncp_cidadeB`...). O cache nunca mistura nomes de API
> diferentes.

### Medir antes de confiar

Teste de carga incluído (Java 21, sem dependências):

```bash
java -Dstdout.encoding=UTF-8 scripts/CargaTeste.java \
  "http://servidor-gateway:8080/extrair?url=https://pncp.gov.br/...&campo=x" 50 5000
```

Isso simula 50 chamadas simultâneas, 5000 no total, e mostra vazão e latência (p50/p95/p99).
Use `{n}` na URL para variar o pedido (ex.: `onde=numero={n}`) e simular cidades consultando
coisas diferentes.

Para acompanhar em produção: `GET /estatisticas` mostra chamadas à API externa, acertos de cache,
pedidos juntados, falhas e quantos receberam 503. Se `saturados_503` sobe, aumente
`max-concurrent-per-host` (se a API externa aguentar) ou suba mais instâncias do gateway.

### Crescendo

O gateway não guarda estado além do cache, então dá para subir **várias instâncias atrás de um
balanceador**. Cada uma tem o seu cache.

---

## 8. Boas práticas

1. **Nunca** coloque token ou senha no PL/SQL: use `p_api` e deixe no gateway.
2. Trate `NULL` como "não encontrado", não como erro.
3. Confirme o formato do JSON da API (abra a URL no navegador) antes de escolher
   `p_lista`/`p_campo`.
4. Um `p_onde` preciso evita trazer dados à toa; o gateway só faz igualdade (sem `>`, `LIKE` ou OR).
5. Não envie dados sensíveis desnecessários na URL: ela passa pela rede interna em texto simples
   (HTTP) e pode aparecer em logs do seu lado.

---

## 9. Operação (infraestrutura)

- **Saúde:** `GET /saude` responde `OK`.
- **Estatísticas:** `GET /estatisticas`, texto `chave=valor`.
- **Logs:** método, host, caminho, status e tempo de cada chamada externa. A query string não é
  registrada (pode ter segredo).
- **Docker:** `Dockerfile` incluído. Monte o `application.yml` externo em `/app/config/`.
- **Alterou `gateway.hosts` ou `gateway.apis`?** Reinicie o gateway.
- **Rede:** só rede interna. O gateway aceita URLs; a lista `gateway.hosts` é o que impede o uso
  para chamar qualquer endereço.

## 10. Limitações

- Resposta ao Oracle de até 32767 caracteres (limite do `VARCHAR2` do 9i).
- Filtro só por igualdade; sem paginação automática (cada chamada traz uma página da API externa).
- Autenticação complexa (assinatura, OAuth com fluxo próprio) ou várias chamadas encadeadas
  exigem um conector no gateway (código Java).
