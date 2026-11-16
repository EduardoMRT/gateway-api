-- PKG_GATEWAY: cliente do gateway-api para Oracle 9i.
-- NAO FOI TESTADO em Oracle 9i (nao havia banco disponivel). Compile e rode os exemplos de
-- 03_exemplos.sql contra um gateway de teste antes de usar em producao.
--
-- Pontos a conferir no 9i:
--   * UTL_HTTP.WRITE_RAW e UTL_RAW.CAST_TO_RAW (usados no ENVIAR)
--   * funcoes pipelined (usadas no LISTAR)
--   * o usuario precisa de ACL/permissao de rede para o host do gateway, se aplicavel

CREATE OR REPLACE PACKAGE PKG_GATEWAY AS

    -- Endereco do gateway (HTTP puro, rede interna). Ajuste conforme o ambiente.
    C_BASE CONSTANT VARCHAR2(200) := 'http://gateway-interno:8080';

    -- GET na API externa; devolve UM valor (campo do primeiro item que bate com o filtro).
    -- NULL = nada encontrado (gateway devolveu 404). Erro = RAISE -20100.
    --   p_url   URL completa da API externa
    --   p_lista caminho do array no JSON (NULL se a raiz ja e o array). Ex.: 'data.itens'
    --   p_onde  filtro 'campo=valor' (varios separados por ';'). Ex.: 'numeroEmpenho=123-1'
    --   p_campo campo a devolver. Aceita caminho com ponto. Ex.: 'sequencialEmpenho'
    --   p_api   nome logico da API (gateway injeta token/headers); NULL se nao precisa
    FUNCTION EXTRAIR(p_url   IN VARCHAR2,
                     p_lista IN VARCHAR2 DEFAULT NULL,
                     p_onde  IN VARCHAR2 DEFAULT NULL,
                     p_campo IN VARCHAR2,
                     p_api   IN VARCHAR2 DEFAULT NULL) RETURN VARCHAR2;

    -- GET na API externa; devolve VARIOS itens como tabela (use com TABLE(...)).
    --   p_campos ate 10 campos separados por virgula. Ex.: 'numero,sequencial,descricao'
    --   p_max    maximo de linhas (o gateway tambem aplica o seu proprio limite)
    FUNCTION LISTAR(p_url    IN VARCHAR2,
                    p_lista  IN VARCHAR2 DEFAULT NULL,
                    p_onde   IN VARCHAR2 DEFAULT NULL,
                    p_campos IN VARCHAR2,
                    p_max    IN NUMBER   DEFAULT NULL,
                    p_api    IN VARCHAR2 DEFAULT NULL) RETURN T_GW_TAB PIPELINED;

    -- Envia p_corpo (JSON) para a API externa com POST (ou PUT/PATCH/DELETE em p_metodo).
    -- Devolve p_campo da resposta; se p_campo for NULL, devolve o corpo bruto da resposta.
    -- POST nunca e repetido automaticamente pelo gateway.
    FUNCTION ENVIAR(p_url    IN VARCHAR2,
                    p_corpo  IN VARCHAR2,
                    p_campo  IN VARCHAR2 DEFAULT NULL,
                    p_metodo IN VARCHAR2 DEFAULT 'POST',
                    p_api    IN VARCHAR2 DEFAULT NULL) RETURN VARCHAR2;

END PKG_GATEWAY;
/

CREATE OR REPLACE PACKAGE BODY PKG_GATEWAY AS

    C_SEP CONSTANT VARCHAR2(1) := '|';
    C_LF  CONSTANT VARCHAR2(1) := CHR(10);

    -- Codifica em percent-encoding (UTF-8), sem depender de UTL_URL.
    FUNCTION url_encode(p_texto IN VARCHAR2) RETURN VARCHAR2 IS
        v_out VARCHAR2(32767);
        v_ch  VARCHAR2(4);
        v_hex VARCHAR2(40);
        j     PLS_INTEGER;
    BEGIN
        IF p_texto IS NULL THEN
            RETURN NULL;
        END IF;
        FOR i IN 1 .. LENGTH(p_texto) LOOP
            v_ch := SUBSTR(p_texto, i, 1);
            IF v_ch BETWEEN 'A' AND 'Z' OR v_ch BETWEEN 'a' AND 'z'
               OR v_ch BETWEEN '0' AND '9' OR v_ch IN ('-', '_', '.', '~') THEN
                v_out := v_out || v_ch;
            ELSE
                v_hex := RAWTOHEX(UTL_RAW.CAST_TO_RAW(CONVERT(v_ch, 'UTF8')));
                j := 1;
                WHILE j <= LENGTH(v_hex) LOOP
                    v_out := v_out || '%' || SUBSTR(v_hex, j, 2);
                    j := j + 2;
                END LOOP;
            END IF;
        END LOOP;
        RETURN v_out;
    END url_encode;

    -- Parametro opcional: so entra na URL se tiver valor.
    FUNCTION param(p_nome IN VARCHAR2, p_valor IN VARCHAR2) RETURN VARCHAR2 IS
    BEGIN
        IF p_valor IS NULL THEN
            RETURN NULL;
        END IF;
        RETURN '&' || p_nome || '=' || url_encode(p_valor);
    END param;

    -- Chamada ao gateway. p_corpo NULL = GET; senao POST com o corpo.
    -- Devolve o corpo da resposta; NULL quando o gateway responde 404.
    FUNCTION chamar(p_url_gw IN VARCHAR2, p_corpo IN VARCHAR2) RETURN VARCHAR2 IS
        v_req    UTL_HTTP.REQ;
        v_resp   UTL_HTTP.RESP;
        v_buf    VARCHAR2(32767);
        v_out    VARCHAR2(32767);
        v_status PLS_INTEGER;
        v_pos    PLS_INTEGER := 1;
        v_len    PLS_INTEGER;
    BEGIN
        UTL_HTTP.SET_TRANSFER_TIMEOUT(120);      -- antes do BEGIN_REQUEST
        IF p_corpo IS NULL THEN
            v_req := UTL_HTTP.BEGIN_REQUEST(p_url_gw, 'GET', 'HTTP/1.1');
        ELSE
            v_req := UTL_HTTP.BEGIN_REQUEST(p_url_gw, 'POST', 'HTTP/1.1');
            UTL_HTTP.SET_HEADER(v_req, 'Content-Type', 'application/json; charset=UTF-8');
            UTL_HTTP.SET_HEADER(v_req, 'Transfer-Encoding', 'chunked');
            v_len := LENGTH(p_corpo);
            WHILE v_pos <= v_len LOOP          -- envia em UTF-8, em pedacos
                UTL_HTTP.WRITE_RAW(v_req,
                    UTL_RAW.CAST_TO_RAW(CONVERT(SUBSTR(p_corpo, v_pos, 4000), 'UTF8')));
                v_pos := v_pos + 4000;
            END LOOP;
        END IF;

        v_resp   := UTL_HTTP.GET_RESPONSE(v_req);
        v_status := v_resp.STATUS_CODE;

        BEGIN
            LOOP
                UTL_HTTP.READ_TEXT(v_resp, v_buf, 4000);
                IF LENGTH(v_out) + LENGTH(v_buf) > 32767 THEN
                    UTL_HTTP.END_RESPONSE(v_resp);
                    RAISE_APPLICATION_ERROR(-20101,
                        'Resposta do gateway excede 32767 caracteres; use p_max ou filtre mais.');
                END IF;
                v_out := v_out || v_buf;
            END LOOP;
        EXCEPTION
            WHEN UTL_HTTP.END_OF_BODY THEN NULL;
        END;
        UTL_HTTP.END_RESPONSE(v_resp);

        IF v_status = 404 THEN
            RETURN NULL;
        ELSIF v_status NOT BETWEEN 200 AND 299 THEN
            RAISE_APPLICATION_ERROR(-20100, 'Gateway HTTP ' || v_status || ': ' || SUBSTR(v_out, 1, 500));
        END IF;
        RETURN v_out;
    END chamar;

    FUNCTION EXTRAIR(p_url   IN VARCHAR2,
                     p_lista IN VARCHAR2 DEFAULT NULL,
                     p_onde  IN VARCHAR2 DEFAULT NULL,
                     p_campo IN VARCHAR2,
                     p_api   IN VARCHAR2 DEFAULT NULL) RETURN VARCHAR2 IS
    BEGIN
        RETURN chamar(C_BASE || '/extrair?url=' || url_encode(p_url)
                      || param('lista', p_lista) || param('onde', p_onde)
                      || param('campo', p_campo) || param('api', p_api), NULL);
    END EXTRAIR;

    FUNCTION LISTAR(p_url    IN VARCHAR2,
                    p_lista  IN VARCHAR2 DEFAULT NULL,
                    p_onde   IN VARCHAR2 DEFAULT NULL,
                    p_campos IN VARCHAR2,
                    p_max    IN NUMBER   DEFAULT NULL,
                    p_api    IN VARCHAR2 DEFAULT NULL) RETURN T_GW_TAB PIPELINED IS
        v_txt   VARCHAR2(32767);
        v_ini   PLS_INTEGER := 1;
        v_fim   PLS_INTEGER;
        v_linha VARCHAR2(32767);
        v_c     T_GW_LINHA;
        TYPE t_cols IS TABLE OF VARCHAR2(4000) INDEX BY BINARY_INTEGER;
        v_col   t_cols;
        n       PLS_INTEGER;
        p1      PLS_INTEGER;
        p2      PLS_INTEGER;
    BEGIN
        v_txt := chamar(C_BASE || '/extrair?url=' || url_encode(p_url)
                        || param('lista', p_lista) || param('onde', p_onde)
                        || param('campo', p_campos) || '&modo=todos'
                        || param('max', TO_CHAR(p_max)) || param('api', p_api), NULL);
        IF v_txt IS NULL THEN
            RETURN;
        END IF;

        WHILE v_ini <= LENGTH(v_txt) LOOP
            v_fim := INSTR(v_txt, C_LF, v_ini);
            IF v_fim = 0 THEN
                v_fim := LENGTH(v_txt) + 1;
            END IF;
            v_linha := SUBSTR(v_txt, v_ini, v_fim - v_ini);
            v_ini   := v_fim + 1;

            -- separa os campos da linha por C_SEP (ate 10)
            v_col.DELETE;
            n  := 0;
            p1 := 1;
            LOOP
                n  := n + 1;
                p2 := INSTR(v_linha, C_SEP, p1);
                IF p2 = 0 THEN
                    v_col(n) := SUBSTR(v_linha, p1);
                    EXIT;
                END IF;
                v_col(n) := SUBSTR(v_linha, p1, p2 - p1);
                p1 := p2 + 1;
                EXIT WHEN n >= 10;
            END LOOP;

            v_c := T_GW_LINHA(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
            IF v_col.EXISTS(1)  THEN v_c.c1  := v_col(1);  END IF;
            IF v_col.EXISTS(2)  THEN v_c.c2  := v_col(2);  END IF;
            IF v_col.EXISTS(3)  THEN v_c.c3  := v_col(3);  END IF;
            IF v_col.EXISTS(4)  THEN v_c.c4  := v_col(4);  END IF;
            IF v_col.EXISTS(5)  THEN v_c.c5  := v_col(5);  END IF;
            IF v_col.EXISTS(6)  THEN v_c.c6  := v_col(6);  END IF;
            IF v_col.EXISTS(7)  THEN v_c.c7  := v_col(7);  END IF;
            IF v_col.EXISTS(8)  THEN v_c.c8  := v_col(8);  END IF;
            IF v_col.EXISTS(9)  THEN v_c.c9  := v_col(9);  END IF;
            IF v_col.EXISTS(10) THEN v_c.c10 := v_col(10); END IF;
            PIPE ROW (v_c);
        END LOOP;
        RETURN;
    END LISTAR;

    FUNCTION ENVIAR(p_url    IN VARCHAR2,
                    p_corpo  IN VARCHAR2,
                    p_campo  IN VARCHAR2 DEFAULT NULL,
                    p_metodo IN VARCHAR2 DEFAULT 'POST',
                    p_api    IN VARCHAR2 DEFAULT NULL) RETURN VARCHAR2 IS
    BEGIN
        RETURN chamar(C_BASE || '/enviar?url=' || url_encode(p_url)
                      || param('metodo', p_metodo) || param('campo', p_campo)
                      || param('api', p_api), NVL(p_corpo, ' '));
    END ENVIAR;

END PKG_GATEWAY;
/
