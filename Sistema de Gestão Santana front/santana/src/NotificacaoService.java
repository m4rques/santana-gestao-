import br.com.oficina.Notificacoes;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

final class NotificacaoService {
    private static final int MAX_TENTATIVAS = 5;
    private static final int LIMITE_POR_CANAL = 10;

    private NotificacaoService() {}

    static void enfileirar(Connection c, long clienteId, String evento, String referencia, String assunto,
                         String mensagem, Instant agendadaPara) throws SQLException {
        Map<String, Object> cliente = Banco.um(c, "SELECT email, telefone FROM clientes WHERE id=? AND consentimento", clienteId);
        if (cliente == null) return;
        enfileirarCanal(c, clienteId, evento, referencia, assunto, mensagem, agendadaPara, "EMAIL", (String) cliente.get("email"));
        enfileirarCanal(c, clienteId, evento, referencia, assunto, mensagem, agendadaPara, "SMS", (String) cliente.get("telefone"));
    }

    private static void enfileirarCanal(Connection c, long cliente, String evento, String referencia, String assunto,
                                       String mensagem, Instant horario, String canal, String destino) throws SQLException {
        if (destino == null || destino.isBlank()) return;
        Banco.executar(c, """
            INSERT INTO oficina_notificacoes (id, cliente_id, evento, referencia, canal, destino, assunto, mensagem, disponivel_em)
            VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (evento, referencia, canal) DO NOTHING
            """, UUID.randomUUID(), cliente, evento, referencia, canal, destino.trim(), assunto, mensagem, horario.atOffset(ZoneOffset.UTC));
    }

    static void cancelarPendente(Connection c, String evento, String referencia) throws SQLException {
        Banco.executar(c, """
            UPDATE oficina_notificacoes SET estado='CANCELADA', reserva=NULL
            WHERE evento=? AND referencia=? AND estado IN ('PENDENTE', 'EM_ENVIO')
            """, evento, referencia);
    }

    static ScheduledExecutorService iniciar(Map<String, Notificacoes.Canal> canais, Duration intervalo) {
        Map<String, Notificacoes.Canal> configurados = Map.copyOf(canais);
        return Notificacoes.iniciar(() -> {
            try (Connection c = Banco.abrir()) {
                processar(c, configurados);
            } catch (SQLException e) {
                throw new IllegalStateException("Falha de acesso à fila (SQLState " + e.getSQLState() + ").", e);
            }
        }, intervalo, e -> System.err.println(e.getMessage()));
    }

    static void processar(Connection c, Map<String, Notificacoes.Canal> canais) throws SQLException {
        if (!c.getAutoCommit()) throw new IllegalArgumentException("A conexão de envio deve confirmar cada reserva automaticamente.");
        Banco.executar(c, """
            UPDATE oficina_notificacoes SET estado='FALHOU', reserva=NULL, ultimo_erro='Reserva de envio expirada'
            WHERE estado='EM_ENVIO' AND tentativas=? AND disponivel_em <= clock_timestamp()
            """, MAX_TENTATIVAS);
        for (var canal : canais.entrySet()) {
            for (int i = 0; i < LIMITE_POR_CANAL && !Thread.currentThread().isInterrupted(); i++) {
                UUID reserva = UUID.randomUUID();
                Map<String, Object> n = reservar(c, canal.getKey(), reserva);
                if (n == null) break;
                enviar(c, canal.getValue(), n, reserva);
            }
        }
    }

    private static Map<String, Object> reservar(Connection c, String canal, UUID reserva) throws SQLException {
        // O UPDATE em autocommit torna a reserva visível antes de chamar a rede.
        return Banco.um(c, """
            WITH selecionada AS (
                SELECT id FROM oficina_notificacoes
                WHERE canal=? AND estado IN ('PENDENTE', 'EM_ENVIO') AND tentativas<?
                    AND disponivel_em <= clock_timestamp()
                ORDER BY disponivel_em, id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE oficina_notificacoes n
            SET estado='EM_ENVIO', reserva=?, tentativas=n.tentativas+1,
                disponivel_em=clock_timestamp()+interval '2 minutes'
            FROM selecionada s WHERE n.id=s.id
            RETURNING n.id, n.destino, n.assunto, n.mensagem, n.tentativas
            """, canal, MAX_TENTATIVAS, reserva);
    }

    private static void enviar(Connection c, Notificacoes.Canal canal, Map<String, Object> n, UUID reserva) throws SQLException {
        UUID id = (UUID) n.get("id");
        try {
            canal.enviar((String) n.get("destino"), (String) n.get("assunto"), (String) n.get("mensagem"), id.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return; // A reserva expira mesmo se o servidor for desligado durante o envio.
        } catch (Exception e) {
            String estado = Banco.longo(n.get("tentativas")) == MAX_TENTATIVAS ? "FALHOU" : "PENDENTE";
            Banco.executar(c, """
                UPDATE oficina_notificacoes SET estado=?, reserva=NULL, ultimo_erro=?,
                    disponivel_em=clock_timestamp()+interval '1 minute'
                WHERE id=? AND reserva=?
                """, estado, e.getClass().getSimpleName(), id, reserva);
            return;
        }
        Banco.executar(c, """
            UPDATE oficina_notificacoes SET estado='ENVIADA', reserva=NULL, enviado_em=clock_timestamp(), ultimo_erro=NULL
            WHERE id=? AND reserva=?
            """, id, reserva);
    }
}
