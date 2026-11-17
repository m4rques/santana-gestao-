BEGIN;

CREATE TABLE IF NOT EXISTS oficina_tecnicos (
    id          SERIAL PRIMARY KEY,
    nome        VARCHAR(120) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS oficina_agendamentos (
    id          BIGSERIAL PRIMARY KEY,
    veiculo_id  INT NOT NULL REFERENCES veiculos(id),
    cliente_id  INT NOT NULL REFERENCES clientes(id),
    tecnico_id  INT NOT NULL REFERENCES oficina_tecnicos(id),
    inicio      TIMESTAMPTZ NOT NULL,
    fim         TIMESTAMPTZ NOT NULL CHECK (fim > inicio),
    estado      VARCHAR(12) NOT NULL DEFAULT 'AGENDADO'
                CHECK (estado IN ('AGENDADO', 'CONVERTIDO', 'CANCELADO')),
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS oficina_agenda_veiculo_idx
    ON oficina_agendamentos (veiculo_id, inicio) WHERE estado <> 'CANCELADO';
CREATE INDEX IF NOT EXISTS oficina_agenda_tecnico_idx
    ON oficina_agendamentos (tecnico_id, inicio) WHERE estado <> 'CANCELADO';
CREATE INDEX IF NOT EXISTS oficina_agenda_criada_idx ON oficina_agendamentos (criado_em);

CREATE TABLE IF NOT EXISTS oficina_pecas (
    id             BIGSERIAL PRIMARY KEY,
    codigo         VARCHAR(40) NOT NULL UNIQUE,
    descricao      VARCHAR(120) NOT NULL,
    valor_unitario NUMERIC(12,2) NOT NULL CHECK (valor_unitario > 0),
    saldo          INTEGER NOT NULL CHECK (saldo >= 0)
);

CREATE TABLE IF NOT EXISTS oficina_os (
    id              BIGSERIAL PRIMARY KEY,
    agendamento_id  BIGINT NOT NULL UNIQUE REFERENCES oficina_agendamentos(id),
    estado          VARCHAR(12) NOT NULL DEFAULT 'ABERTA'
                    CHECK (estado IN ('ABERTA', 'EM_EXECUCAO', 'CONCLUIDA', 'FATURADA')),
    subtotal        NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (subtotal >= 0),
    aberta_em       TIMESTAMPTZ NOT NULL DEFAULT now(),
    concluida_em    TIMESTAMPTZ,
    CHECK ((concluida_em IS NOT NULL) = (estado IN ('CONCLUIDA', 'FATURADA')))
);

CREATE INDEX IF NOT EXISTS oficina_os_aberta_idx ON oficina_os (aberta_em);
CREATE INDEX IF NOT EXISTS oficina_os_concluida_idx ON oficina_os (concluida_em);

CREATE TABLE IF NOT EXISTS oficina_itens (
    id             BIGSERIAL PRIMARY KEY,
    os_id          BIGINT NOT NULL REFERENCES oficina_os(id),
    tipo           VARCHAR(7) NOT NULL CHECK (tipo IN ('PECA', 'SERVICO')),
    peca_id        BIGINT REFERENCES oficina_pecas(id),
    descricao      VARCHAR(120) NOT NULL,
    quantidade     INT NOT NULL CHECK (quantidade BETWEEN 1 AND 1000000),
    valor_unitario NUMERIC(12,2) NOT NULL CHECK (valor_unitario > 0),
    CHECK ((tipo = 'PECA') = (peca_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS oficina_itens_os_idx ON oficina_itens (os_id);

CREATE TABLE IF NOT EXISTS oficina_faturas (
    id          BIGSERIAL PRIMARY KEY,
    os_id       BIGINT NOT NULL UNIQUE REFERENCES oficina_os(id),
    subtotal    NUMERIC(18,2) NOT NULL CHECK (subtotal > 0),
    aliquota    NUMERIC(7,6) NOT NULL CHECK (aliquota BETWEEN 0 AND 1),
    imposto     NUMERIC(18,2) NOT NULL CHECK (imposto = round(subtotal * aliquota, 2)),
    total       NUMERIC(19,2) NOT NULL CHECK (total = subtotal + imposto),
    emitida_em  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS oficina_faturas_emitida_idx ON oficina_faturas (emitida_em);

COMMIT;
