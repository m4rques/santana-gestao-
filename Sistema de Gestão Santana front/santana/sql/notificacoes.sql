BEGIN;

CREATE TABLE IF NOT EXISTS oficina_notificacoes (
    id              UUID PRIMARY KEY,
    cliente_id      INT NOT NULL REFERENCES clientes(id),
    evento          VARCHAR(50) NOT NULL,
    referencia      VARCHAR(80) NOT NULL,
    canal           VARCHAR(5) NOT NULL CHECK (canal IN ('EMAIL', 'SMS')),
    destino         TEXT NOT NULL,
    assunto         TEXT NOT NULL,
    mensagem        TEXT NOT NULL,
    estado          VARCHAR(10) NOT NULL DEFAULT 'PENDENTE'
                    CHECK (estado IN ('PENDENTE', 'EM_ENVIO', 'ENVIADA', 'FALHOU', 'CANCELADA')),
    disponivel_em   TIMESTAMPTZ NOT NULL,
    reserva         UUID,
    tentativas      INT NOT NULL DEFAULT 0 CHECK (tentativas BETWEEN 0 AND 5),
    enviado_em      TIMESTAMPTZ,
    ultimo_erro     TEXT,
    UNIQUE (evento, referencia, canal),
    CHECK ((estado = 'EM_ENVIO') = (reserva IS NOT NULL)),
    CHECK ((estado = 'ENVIADA') = (enviado_em IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS oficina_notificacoes_prontas_idx
    ON oficina_notificacoes (canal, disponivel_em) WHERE estado IN ('PENDENTE', 'EM_ENVIO');

COMMIT;
