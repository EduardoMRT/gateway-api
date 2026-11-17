# gateway-api

Gateway HTTP para o **Oracle 9i** consumir APIs modernas sem sofrer com TLS e JSON.

```
Oracle 9i  --HTTP puro, rede interna-->  gateway-api  --HTTPS/TLS 1.2+-->  PNCP e outras APIs
```

O Oracle 9i não fala TLS moderno e não tem funções de JSON. O gateway faz a chamada HTTPS,
parseia o JSON, filtra e devolve só o valor pedido, em texto puro. Para o PL/SQL, consumir uma
API vira uma chamada de função.

## Antes: ~165 linhas de PL/SQL (HTTP + `INSTR`/`SUBSTR` no JSON)

## Depois

```sql
v_seq := TO_NUMBER(PKG_GATEWAY.EXTRAIR(
             p_url   => V_URL,
             p_onde  => 'numeroEmpenho=123-1',
             p_campo => 'sequencialEmpenho'));
```

Mais exemplos (lista em SQL com `TABLE(...)`, POST) em [`oracle/03_exemplos.sql`](oracle/03_exemplos.sql).
**Manual completo:** [`docs/MANUAL.md`](docs/MANUAL.md) · [PDF](docs/MANUAL.pdf)
(regenerar o PDF: `python3 scripts/gerar_pdf.py`). O contrato completo (parâmetros, formatos,
erros) está em [`docs/CONTRATO.md`](docs/CONTRATO.md).

## Desempenho (muitas cidades ao mesmo tempo)

Threads virtuais (Java 21), conexões reaproveitadas, **cache curto** para GET, **junção de pedidos
idênticos simultâneos** (50 cidades pedindo a mesma coisa = 1 ida à API externa), limite de
chamadas por host e contadores em `/estatisticas`. Teste de carga em `scripts/CargaTeste.java`.

## Estrutura

| Pasta | Conteúdo |
|---|---|
| `src/` | Gateway em Spring Boot 3 / Java 21 |
| `oracle/` | `PKG_GATEWAY` e tipos para o Oracle 9i + exemplos |
| `docs/MANUAL.md`, `docs/MANUAL.pdf` | Manual completo (conceitos, diagramas, uso, SSL, listas, problemas) |
| `docs/img/` | Diagramas do manual (fontes Graphviz em `docs/img/fonte/`) |
| `docs/CONTRATO.md` | Contrato do gateway |
| `scripts/CargaTeste.java` | Teste de carga (Java 21, sem dependências) |

## Rodando

```bash
mvn test                 # testes de integração (sobem uma "API externa" falsa)
mvn package
java -jar target/gateway-api-0.1.0.jar
curl http://localhost:8080/saude
```

Config em `src/main/resources/application.yml` (ou um `application.yml` externo / variáveis de
ambiente). **Para o gateway chamar uma API, o host dela precisa estar liberado**: variável de ambiente `GATEWAY_HOSTS=pncp.gov.br,viacep.com.br`, o `application.yml`,
ou um arquivo de texto (`GATEWAY_HOSTS_FILE`, veja `hosts.exemplo.txt`) que vale **sem reiniciar**. Sem
isso, 403. Também dá para definir SSL por host e headers/tokens por API lógica.

### Instalando o lado Oracle

```
@oracle/01_tipos.sql
@oracle/02_pkg_gateway.sql
```

Ajuste `G_BASE` em `PKG_GATEWAY` para o endereço do gateway (ou, só na sessão: `BEGIN PKG_GATEWAY.G_BASE := 'http://IP:8080'; END;`).

## Segurança

- Expor o gateway **só na rede interna**. Ele aceita URLs, então a allowlist de hosts é o que
  impede de usá-lo para chamar qualquer coisa.
- Credenciais ficam na config do gateway, nunca no PL/SQL.
- `insecure: true` em um host desliga a validação do certificado dele. Use só em homologação.

## Status de verificação

- **Extração de JSON** (`JsonExtractor`): verificada com 11 casos, incluindo objetos aninhados e
  `}` dentro de strings.
- **Cliente HTTP, cache e concorrência** (`ExternalApiClient`, `SingleFlightCache`): verificados
  com 33 checagens executadas contra uma API externa falsa (compilados com stubs do Spring):
  allowlist, retry só em GET, POST sem retry, headers por API, cache, 100 pedidos idênticos
  simultâneos = 1 chamada externa, limite por host e 503 por saturação.
- **Capacidade real** (req/s do gateway em produção) **não foi medida**: depende da API externa e
  do servidor. Use `scripts/CargaTeste.java` no ambiente real.
- **Testes de integração** (`GatewayIntegrationTest`: allowlist, retry, POST sem retry, headers
  por API, 404, 400): escritos, **mas ainda não executados**. O ambiente em que o projeto foi
  criado não alcançava o Maven Central. Rode `mvn test` antes de usar.
- **`PKG_GATEWAY` (Oracle 9i)**: **não testado**, não havia banco 9i disponível. Veja os pontos a
  conferir no cabeçalho de `oracle/02_pkg_gateway.sql`.
- **Pendente de validar:** se o `UTL_HTTP` do 9i consegue falar com o gateway no ambiente real, e
  se a chamada HTTPS direta do 9i ao PNCP realmente falha (o que justifica o gateway).
