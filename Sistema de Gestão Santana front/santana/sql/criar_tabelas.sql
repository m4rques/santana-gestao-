-- Rode uma vez. Antes, crie o banco:  CREATE DATABASE santana;
-- Depois conecte nele e execute este script inteiro.

CREATE TABLE usuarios (
    id          SERIAL PRIMARY KEY,
    nome        VARCHAR(100) NOT NULL,
    email       VARCHAR(120) NOT NULL UNIQUE,
    senha_hash  CHAR(64)     NOT NULL,   -- SHA-256 em hexadecimal
    perfil      VARCHAR(30)  NOT NULL    -- Administrador, Gerente de Vendas, Vendedor, Recepcionista
);

CREATE SEQUENCE cliente_codigo_seq;      -- usada para gerar o código único CLI-0001, CLI-0002...

CREATE TABLE clientes (
    id            SERIAL PRIMARY KEY,
    codigo        VARCHAR(12)  NOT NULL UNIQUE,
    nome          VARCHAR(120) NOT NULL,
    cpf           VARCHAR(14)  NOT NULL UNIQUE,   -- só dígitos (CPF ou CNPJ)
    telefone      VARCHAR(30),
    email         VARCHAR(120),
    endereco      VARCHAR(200),
    cnh_arquivo   VARCHAR(200),
    cnh_status    VARCHAR(20)  NOT NULL DEFAULT 'Não informada',
    consentimento BOOLEAN      NOT NULL,
    criado_em     TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE veiculos (
    id      SERIAL PRIMARY KEY,
    chassi  VARCHAR(30)   NOT NULL UNIQUE,
    marca   VARCHAR(50)   NOT NULL,
    modelo  VARCHAR(50)   NOT NULL,
    ano     INT           NOT NULL,
    tipo    VARCHAR(10)   NOT NULL CHECK (tipo IN ('Novo', 'Seminovo')),
    km      INT           NOT NULL DEFAULT 0 CHECK (km >= 0),
    preco   NUMERIC(12,2) NOT NULL CHECK (preco > 0),
    status  VARCHAR(20)   NOT NULL DEFAULT 'Disponível'
);

CREATE TABLE test_drives (
    id          SERIAL PRIMARY KEY,
    cliente_id  INT       NOT NULL REFERENCES clientes(id),
    veiculo_id  INT       NOT NULL REFERENCES veiculos(id),
    vendedor_id INT       NOT NULL REFERENCES usuarios(id),
    data_hora   TIMESTAMP NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'Agendado',
    feedback    TEXT
);

CREATE TABLE propostas (
    id           SERIAL PRIMARY KEY,
    cliente_id   INT           NOT NULL REFERENCES clientes(id),
    veiculo_id   INT           NOT NULL REFERENCES veiculos(id),
    vendedor_id  INT           NOT NULL REFERENCES usuarios(id),
    valor_tabela NUMERIC(12,2) NOT NULL,
    desconto_pct NUMERIC(5,2)  NOT NULL DEFAULT 0,
    valor_final  NUMERIC(12,2) NOT NULL,
    pagamento    VARCHAR(100)  NOT NULL,
    status       VARCHAR(25)   NOT NULL,   -- Aguardando aprovação, Aprovada, Recusada, Vendida
    aprovado_por INT REFERENCES usuarios(id),
    criado_em    TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE TABLE vendas (
    id          SERIAL PRIMARY KEY,
    proposta_id INT           NOT NULL UNIQUE REFERENCES propostas(id),
    data        DATE          NOT NULL,
    valor       NUMERIC(12,2) NOT NULL,
    pagamento   VARCHAR(100)  NOT NULL,
    cliente_id  INT           NOT NULL REFERENCES clientes(id),
    veiculo_id  INT           NOT NULL REFERENCES veiculos(id)
);

-- Dados de demonstração. As senhas são guardadas como SHA-256 (só para a demo).
INSERT INTO usuarios (nome, email, senha_hash, perfil) VALUES
 ('Administrador',  'admin@santana.com',    encode(sha256('Admin@123'::bytea),    'hex'), 'Administrador'),
 ('Gerente Teste',  'gerente@santana.com',  encode(sha256('Gerente@123'::bytea),  'hex'), 'Gerente de Vendas'),
 ('Vendedor Teste', 'vendedor@santana.com', encode(sha256('Vendedor@123'::bytea), 'hex'), 'Vendedor'),
 ('Recepção Teste', 'recepcao@santana.com', encode(sha256('Recepcao@123'::bytea), 'hex'), 'Recepcionista');

INSERT INTO veiculos (chassi, marca, modelo, ano, tipo, km, preco) VALUES
 ('CH000001', 'Toyota',     'Corolla', 2024, 'Novo',     0,     165000),
 ('CH000002', 'Honda',      'Civic',   2022, 'Seminovo', 31000, 125000),
 ('CH000003', 'Volkswagen', 'Polo',    2023, 'Seminovo', 18000, 89000),
 ('CH000004', 'Fiat',       'Argo',    2024, 'Novo',     0,     82000);
