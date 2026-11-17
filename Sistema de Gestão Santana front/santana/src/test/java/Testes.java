import br.com.oficina.ContratoPdfTeste;
import br.com.oficina.NotificacoesTeste;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;

public final class Testes {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("santana-testes-");
        try {
            ContratoPdfTeste.executar(dir);
            NotificacoesTeste.executar(dir);
            validacoes();
            System.out.println("Testes unitários passaram (PDF, configuração, agenda, dashboard e notificações).");
        } finally {
            try (var arquivos = Files.walk(dir)) {
                for (Path caminho : arquivos.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(caminho);
                }
            }
        }
    }

    private static void validacoes() throws Exception {
        igual(new BigDecimal("0.050000"), OficinaService.aliquota("0.05"));
        igual(new BigDecimal("0.000000"), OficinaService.aliquota("0"));
        igual(new BigDecimal("1.000000"), OficinaService.aliquota("1"));
        for (String taxa : new String[] { "-0.01", "1.01", "0.0000001", "5%" }) {
            falha(IllegalArgumentException.class, () -> OficinaService.aliquota(taxa));
        }
        igual(Instant.parse("2030-02-01T12:30:00Z"),
            OficinaService.horario(Map.of("inicio", "2030-02-01T09:30:00-03:00"), "inicio").toInstant());
        falha(ErroNegocio.class, () -> OficinaService.horario(Map.of("inicio", "2030-02-01T09:30:00"), "inicio"));
        falha(ErroNegocio.class, () -> DashboardService.relatorio(null, Map.of("meses", "0")));
        falha(ErroNegocio.class, () -> DashboardService.relatorio(null, Map.of("inicio", "2030-02-01")));
        falha(ErroNegocio.class, () -> DashboardService.relatorio(null, Map.of("inicio", "2030-02-28", "fim", "2030-02-01")));
    }

    private static void igual(Object esperado, Object obtido) {
        if (!esperado.equals(obtido))
            throw new AssertionError("Esperado " + esperado + ", obtido " + obtido);
    }

    private static void falha(Class<? extends Exception> tipo, Acao acao) throws Exception {
        try {
            acao.executar();
        } catch (Exception e) {
            if (tipo.isInstance(e)) return;
            throw e;
        }
        throw new AssertionError("Era esperada a exceção " + tipo.getSimpleName());
    }

    @FunctionalInterface
    private interface Acao {
        void executar() throws Exception;
    }
}
