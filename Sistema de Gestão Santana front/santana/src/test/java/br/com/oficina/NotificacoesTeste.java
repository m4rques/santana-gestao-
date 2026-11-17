package br.com.oficina;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class NotificacoesTeste {
    private NotificacoesTeste() {
    }

    public static void executar(Path dir) throws Exception {
        testarAgendador();
        testarGatewayHttp();
    }

    private static void testarAgendador() throws InterruptedException {
        RuntimeException falha = new IllegalStateException("Falha simulada no processamento.");
        AtomicReference<RuntimeException> erro = new AtomicReference<>();
        AtomicInteger ciclos = new AtomicInteger();
        CountDownLatch segundoCiclo = new CountDownLatch(1);
        ScheduledExecutorService agendador = Notificacoes.iniciar(() -> {
            if (ciclos.incrementAndGet() == 1) {
                throw falha;
            }
            segundoCiclo.countDown();
        }, Duration.ofMillis(20), erro::set);
        try {
            verificar(segundoCiclo.await(3, TimeUnit.SECONDS), "Agendador não voltou a executar após a falha.");
            verificar(erro.get() == falha, "O agendador deve encaminhar o erro ao tratador.");
        } finally {
            agendador.shutdownNow();
        }
    }

    private static void testarGatewayHttp() throws Exception {
        AtomicReference<Pedido> recebido = new AtomicReference<>();
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            try {
                recebido.set(new Pedido(troca.getRequestMethod(),
                        troca.getRequestHeaders().getFirst("Content-Type"),
                        troca.getRequestHeaders().getFirst("Authorization"),
                        troca.getRequestHeaders().getFirst("Idempotency-Key"),
                        new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                int status = "/enviar".equals(troca.getRequestURI().getPath()) ? 202 : 503;
                troca.sendResponseHeaders(status, -1);
            } finally {
                troca.close();
            }
        });
        servidor.start();
        try {
            String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
            Notificacoes.http(URI.create(base + "/enviar"), "segredo-teste")
                    .enviar("cliente@example.test", "Revisão \"A\"", "Olá!\nPasta: C:\\OS\t\0", "aviso-email-1");
            Pedido pedido = recebido.get();
            verificar("POST".equals(pedido.metodo()), "Gateway deve receber POST.");
            verificar("application/json; charset=utf-8".equals(pedido.tipo()), "Gateway deve receber JSON UTF-8.");
            verificar("Bearer segredo-teste".equals(pedido.autorizacao()), "Gateway não recebeu o token.");
            verificar("aviso-email-1".equals(pedido.chave()), "Gateway não recebeu a chave de idempotência.");
            String corpoEsperado = "{\"destino\":\"cliente@example.test\",\"assunto\":\"Revisão \\\"A\\\"\","
                    + "\"mensagem\":\"Olá!\\nPasta: C:\\\\OS\\t\\u0000\"}";
            verificar(corpoEsperado.equals(pedido.corpo()), "O JSON não preservou os campos da notificação.");

            try {
                Notificacoes.http(URI.create(base + "/indisponivel"), null)
                        .enviar("+5500000000000", "Revisão", "Olá!", "aviso-sms-1");
                throw new AssertionError("HTTP 503 deveria ser sinalizado como falha.");
            } catch (IOException falha) {
                verificar(falha.getMessage().contains("503"), "A falha deve indicar HTTP 503.");
            }
            verificar(recebido.get().autorizacao() == null, "Gateway sem token recebeu Authorization.");
        } finally {
            servidor.stop(0);
        }
    }

    private static void verificar(boolean condicao, String mensagem) {
        if (!condicao) {
            throw new AssertionError(mensagem);
        }
    }

    private record Pedido(String metodo, String tipo, String autorizacao, String chave, String corpo) {
    }
}
