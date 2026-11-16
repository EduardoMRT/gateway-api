-- Tipos usados por PKG_GATEWAY.LISTAR (função pipelined, compatível com Oracle 9i).
-- Cada linha devolvida tem até 10 colunas de texto, na ordem pedida em p_campos.

CREATE OR REPLACE TYPE T_GW_LINHA AS OBJECT (
    c1  VARCHAR2(4000),
    c2  VARCHAR2(4000),
    c3  VARCHAR2(4000),
    c4  VARCHAR2(4000),
    c5  VARCHAR2(4000),
    c6  VARCHAR2(4000),
    c7  VARCHAR2(4000),
    c8  VARCHAR2(4000),
    c9  VARCHAR2(4000),
    c10 VARCHAR2(4000)
);
/

CREATE OR REPLACE TYPE T_GW_TAB AS TABLE OF T_GW_LINHA;
/
