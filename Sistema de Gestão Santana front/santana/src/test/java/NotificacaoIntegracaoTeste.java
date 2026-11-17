import java.io.IOException;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class NotificacaoIntegracaoTeste {
    private NotificacaoIntegracaoTeste() {}

    static void executar(Connection c) throws Exception {
        long cliente = cliente(c, "TESTE-EMAIL", "12345678901", true);
        long semConsentimento = cliente(c, "TESTE-SEM", "12345678902", false);
        Instant vencida = Instant.now().minusSeconds(10);
        Instant futura = Instant.now().plus(1, ChronoUnit.DAYS);
        NotificacaoService.enfileirar(c, cliente, "TESTE_ENTREGA", "1", "Entrega", "Teste", vencida);
        NotificacaoService.enfileirar(c, cliente, "TESTE_ENTREGA", "1", "Entrega", "Teste", vencida);
        NotificacaoService.enfileirar(c, cliente, "TESTE_ENTREGA", "2", "Futura", "Teste", futura);
        NotificacaoService.enfileirar(c, semConsentimento, "TESTE_ENTREGA", "3", "Sem consentimento", "Teste", vencida);

        Map<String, Object> contagem = Banco.um(c, "SELECT count(*) AS qtd FROM oficina_notificacoes WHERE evento='TESTE_ENTREGA'");
        igual(4L, Banco.longo(contagem.get("qtd")), "deduplicação e consentimento");

        var chave = new AtomicReference<String>();
        NotificacaoService.processar(c, Map.of("EMAIL", (destino, assunto, mensagem, id) -> {
            if (assunto.equals("Entrega")) {
                igual("teste@example.invalid", destino, "destinatário");
                chave.set(id);
                throw new IOException("texto privado da integração");
            }
        }));
        Map<String, Object> primeira = notificacao(c, "1", "EMAIL");
        igual("PENDENTE", primeira.get("estado"), "reenvio após erro transitório");
        igual(1L, Banco.longo(primeira.get("tentativas")), "primeira tentativa registrada");
        igual("IOException", primeira.get("ultimo_erro"), "mensagem externa excluída do registro");
        igual(0L, Banco.longo(notificacao(c, "1", "SMS").get("tentativas")), "canal desabilitado pendente");
        igual(0L, Banco.longo(notificacao(c, "2", "EMAIL").get("tentativas")), "horário futuro aguardado");

        Banco.executar(c, """
            UPDATE oficina_notificacoes SET disponivel_em=clock_timestamp() - interval '1 second'
            WHERE id=?
            """, primeira.get("id"));
        NotificacaoService.processar(c, Map.of("EMAIL", (destino, assunto, mensagem, id) -> {
            if (assunto.equals("Entrega")) igual(chave.get(), id, "mesma chave no reenvio");
        }));
        Map<String, Object> segunda = notificacao(c, "1", "EMAIL");
        igual("ENVIADA", segunda.get("estado"), "envio após reprocessamento");
        igual(2L, Banco.longo(segunda.get("tentativas")), "segunda tentativa registrada");

        Banco.executar(c, """
            UPDATE oficina_notificacoes SET estado='EM_ENVIO', reserva=?, tentativas=5,
                disponivel_em=clock_timestamp() - interval '1 second'
            WHERE id=?
            """, UUID.randomUUID(), notificacao(c, "1", "SMS").get("id"));
        NotificacaoService.processar(c, Map.of("SMS", (destino, assunto, mensagem, id) -> {
            if (assunto.equals("Entrega")) throw new AssertionError("Envio repetido após o limite.");
        }));
        igual("FALHOU", notificacao(c, "1", "SMS").get("estado"), "reserva expirada após limite de tentativas");
    }

    private static long cliente(Connection c, String codigo, String cpf, boolean consentimento) throws Exception {
        return Banco.longo(Banco.um(c, """
            INSERT INTO clientes (codigo, nome, cpf, email, telefone, consentimento)
            VALUES (?, 'Cliente de teste', ?, 'teste@example.invalid', '+5500000000000', ?) RETURNING id
            """, codigo, cpf, consentimento).get("id"));
    }

    private static Map<String, Object> notificacao(Connection c, String referencia, String canal) throws Exception {
        return Banco.um(c, """
            SELECT id, estado, tentativas, ultimo_erro FROM oficina_notificacoes
            WHERE evento='TESTE_ENTREGA' AND referencia=? AND canal=?
            """, referencia, canal);
    }

    private static void igual(Object esperado, Object ocorrido, String caso) {
        if (!esperado.equals(ocorrido)) {
            throw new AssertionError(caso + ": esperado " + esperado + ", obtido " + ocorrido);
        }
    }
}
