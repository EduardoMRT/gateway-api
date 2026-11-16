# Contrato do gateway

Serviço HTTP simples, para ser chamado pelo Oracle 9i (que não consegue falar TLS moderno nem
parsear JSON com conforto). O gateway fala HTTPS/JSON com as APIs externas e devolve **texto puro**.

- Rede: HTTP puro, **somente rede interna** (firewall). Nunca expor à internet.
- Codificação: UTF-8. Respostas sempre `text/plain; charset=UTF-8`.
- Parâmetros vão na query string, codificados em percent-encoding (UTF-8).

## Endpoints

### `GET /extrair`: chama a API externa com GET e extrai valores

| Parâmetro | Obrigatório | Descrição |
|---|---|---|
| `url` | sim | URL completa da API externa (http/https). O host precisa estar na allowlist. |
| `campo` | sim | Campo(s) a devolver, separados por vírgula. Aceita caminho com ponto (`itens.0.id`). |
| `lista` | não | Caminho do array no JSON (`data.itens`). Vazio = a raiz. Se o nó não for array, é tratado como item único. |
| `onde` | não | Filtro `campo=valor`. Vários separados por `;` (todos precisam bater). Comparação como texto, sem espaços nas pontas. |
| `modo` | não | `primeiro` (padrão) ou `todos`. |
| `sep` | não | Separador de campos (1 caractere, padrão `\|`). |
| `max` | não | Máximo de itens em `modo=todos` (nunca passa do `gateway.max-items`). |
| `api` | não | Nome lógico de API: o gateway injeta os headers configurados (token etc.). |

### `POST /enviar`: envia o corpo recebido para a API externa

Mesmos parâmetros de `/extrair` mais:

| Parâmetro | Descrição |
|---|---|
| `metodo` | `POST` (padrão), `PUT`, `PATCH` ou `DELETE`. |
| `campo` | Opcional. Sem `campo`, devolve o corpo bruto da resposta. |

O corpo da requisição ao gateway é repassado como está. `Content-Type` também é repassado
(padrão `application/json`).

### `GET /saude`: responde `OK` (monitoramento)

## Formato das respostas de sucesso (200)

- Um campo, `modo=primeiro`: `42`
- Vários campos: `123-1|42|descrição`
- `modo=todos`: uma linha por item, separadas por `\n` (LF):

```
123-1|42|ok com pipe
123-1|43|segundo
```

Valor ausente ou `null` vira texto vazio. O separador e quebras de linha **dentro** de um valor
são trocados por espaço, para o formato nunca quebrar. Objetos e arrays viram o JSON como texto.

## Códigos de erro

| HTTP | Significado | No PL/SQL |
|---|---|---|
| 200 | Sucesso | valor(es) |
| 404 | Nada encontrado (corpo vazio), ou a API externa respondeu 404 | `NULL` / zero linhas |
| 400 | Parâmetro inválido ou ausente (mensagem no corpo) | ORA-20100 |
| 403 | Host fora da allowlist | ORA-20100 |
| 502 | API externa falhou (status não 2xx, JSON inválido, rede). Mensagem no corpo. | ORA-20100 |
| 504 | Timeout chamando a API externa | ORA-20100 |
| 500 | Erro interno do gateway | ORA-20100 |

## Regras de comportamento

- **Retry:** só `GET` (`retry-max` tentativas extras, em 429/502/503/504 e erros de rede).
  **POST/PUT/PATCH/DELETE nunca são repetidos**, para não duplicar efeito na API externa.
- **Allowlist:** só hosts listados em `gateway.hosts` são chamados. Redirects não são seguidos.
- **SSL por host** (`gateway.hosts`): padrão = validação normal do JDK. Opções por host:
  `trust-store` (certificado próprio da API), `key-store` (certificado de cliente / mTLS) e
  `insecure: true` (aceita qualquer certificado; exceção explícita, só para homologação/interno).
- **Credenciais** ficam só no gateway (`gateway.apis.<nome>.headers`); o Oracle cita só o nome em `api=`.
- **Limites:** `max-response-bytes` (resposta externa), `max-items` (linhas), timeouts de conexão e leitura.
- Logs registram método, host, path, status e tempo. Nunca a query string (pode ter segredo).

## Limitações conhecidas

- Resposta ao Oracle limitada a 32767 caracteres (limite do `VARCHAR2` do 9i). Use `max` e filtros.
- Filtro `onde` só faz igualdade. Não há comparação, `LIKE` ou OR.
- Sem paginação automática da API externa: cada chamada é uma página.
- Conector customizado (autenticação exótica, várias chamadas encadeadas) exige código no gateway.
