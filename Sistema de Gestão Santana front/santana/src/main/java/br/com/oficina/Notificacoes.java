package br.com.oficina;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class Notificacoes {
    private static final System.Logger LOG = System.getLogger(Notificacoes.class.getName());

    private Notificacoes() {
    }

    @FunctionalInterface
    public interface Canal {
        void enviar(String destino, String assunto, String mensagem, String chaveIdempotencia) throws Exception;
    }

    public static ScheduledExecutorService iniciar(
            Runnable processamento, Duration intervalo, Consumer<RuntimeException> erro) {
        Objects.requireNonNull(processamento, "processamento");
        Objects.requireNonNull(intervalo, "intervalo");
        Objects.requireNonNull(erro, "erro");
        if (intervalo.isNegative() || intervalo.isZero()) {
            throw new IllegalArgumentException("O intervalo de notificações deve ser positivo.");
        }
        long intervaloNanos = intervalo.toNanos();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                tarefa -> new Thread(tarefa, "notificacoes"));
        executor.scheduleWithFixedDelay(() -> {
            try {
                processamento.run();
            } catch (RuntimeException falha) {
                try {
                    erro.accept(falha);
                } catch (RuntimeException falhaAoReportar) {
                    LOG.log(System.Logger.Level.ERROR, "Falha no processamento de notificações.", falha);
                    LOG.log(System.Logger.Level.ERROR, "Falha ao reportar erro de notificações.", falhaAoReportar);
                }
            }
        }, 0, intervaloNanos, TimeUnit.NANOSECONDS);
        return executor;
    }

    public static Canal console(String nomeCanal) {
        Objects.requireNonNull(nomeCanal, "nomeCanal");
        return (destino, assunto, mensagem, chave) ->
                System.out.printf("[SIMULAÇÃO %s] %s | %s | %s | %s%n",
                        nomeCanal, chave, destino, assunto, mensagem);
    }

    public static Canal http(URI endpoint, String token) {
        Objects.requireNonNull(endpoint, "endpoint");
        boolean esquemaHttp = "http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme());
        if (!esquemaHttp || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Informe uma URL HTTP ou HTTPS válida para o gateway.");
        }
        HttpClient cliente = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (destino, assunto, mensagem, chave) -> {
            String corpo = "{\"destino\":" + json(destino) + ",\"assunto\":" + json(assunto)
                    + ",\"mensagem\":" + json(mensagem) + "}";
            HttpRequest.Builder pedido = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Idempotency-Key", chave)
                    .POST(HttpRequest.BodyPublishers.ofString(corpo));
            if (token != null && !token.isBlank()) {
                pedido.header("Authorization", "Bearer " + token);
            }
            HttpResponse<Void> resposta;
            try {
                resposta = cliente.send(pedido.build(), HttpResponse.BodyHandlers.discarding());
            } catch (InterruptedException falha) {
                Thread.currentThread().interrupt();
                throw falha;
            }
            int status = resposta.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Gateway de notificações respondeu com HTTP " + status + ".");
            }
        };
    }

    private static String json(String valor) {
        Objects.requireNonNull(valor, "Campo da notificação");
        StringBuilder texto = new StringBuilder(valor.length() + 2).append('"');
        for (int i = 0; i < valor.length(); i++) {
            char caractere = valor.charAt(i);
            switch (caractere) {
                case '"' -> texto.append("\\\"");
                case '\\' -> texto.append("\\\\");
                case '\n' -> texto.append("\\n");
                case '\r' -> texto.append("\\r");
                case '\t' -> texto.append("\\t");
                default -> {
                    if (caractere < 0x20) {
                        texto.append("\\u00");
                        texto.append(Character.forDigit(caractere >> 4, 16));
                        texto.append(Character.forDigit(caractere & 0xf, 16));
                    } else {
                        texto.append(caractere);
                    }
                }
            }
        }
        return texto.append('"').toString();
    }
}
