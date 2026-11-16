-- Exemplos de uso do PKG_GATEWAY (Oracle 9i).

-- 1) Um valor. Substitui as 2 funcoes do anexo original (F_D_CONSULTA_EMPENHO + F_D_SEQ_EMPENHO_JSON
--    faziam o HTTP e o parse na mao). Nada encontrado = NULL.
DECLARE
    v_seq NUMBER;
BEGIN
    v_seq := TO_NUMBER(PKG_GATEWAY.EXTRAIR(
                 p_url   => 'https://pncp.gov.br/api/...',   -- use F_D_URL_API_PNCP(...)
                 p_onde  => 'numeroEmpenho=123-1',
                 p_campo => 'sequencialEmpenho'));
    DBMS_OUTPUT.PUT_LINE('sequencial = ' || v_seq);
END;
/

-- 2) Varios itens, usados direto em SQL (pode juntar com tabelas do sistema).
SELECT c1 AS numero, c2 AS sequencial, c3 AS descricao
FROM   TABLE(CAST(PKG_GATEWAY.LISTAR(
           p_url    => 'https://pncp.gov.br/api/...',
           p_lista  => 'data',
           p_campos => 'numeroEmpenho,sequencialEmpenho,descricao',
           p_max    => 100) AS T_GW_TAB));

-- 3) Envio (POST) devolvendo um campo da resposta.
DECLARE
    v_protocolo VARCHAR2(100);
BEGIN
    v_protocolo := PKG_GATEWAY.ENVIAR(
                       p_url   => 'https://api.exemplo.gov.br/documentos',
                       p_corpo => '{"numero":"123","valor":150.00}',
                       p_campo => 'protocolo',
                       p_api   => 'exemplo');   -- gateway injeta o token configurado
    DBMS_OUTPUT.PUT_LINE('protocolo = ' || v_protocolo);
END;
/

-- Erros: o gateway devolve HTTP != 2xx e o package levanta ORA-20100 com a mensagem.
--   404 (nada encontrado) nao e erro: devolve NULL / zero linhas.
