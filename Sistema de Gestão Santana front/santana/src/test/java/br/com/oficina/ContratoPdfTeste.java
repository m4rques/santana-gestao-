package br.com.oficina;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContratoPdfTeste {
    private ContratoPdfTeste() {
    }

    public static void executar(Path dir) throws IOException {
        Path pasta = Files.createTempDirectory(dir, "contrato-teste-");
        Path template = pasta.resolve("modelo.txt");
        Path destino = pasta.resolve("venda.pdf");
        String comprador = "João (A) \\ $10";
        Map<String, String> dados = Map.of("comprador", comprador);
        String linhaLonga = "X".repeat(80);
        Files.writeString(template, "\uFEFFTÍTULO\nComprador: {{ comprador }}\n"
                + linhaLonga + "\n" + "Cláusula\n".repeat(55) + "Última linha\n");

        Path gerado = ContratoPdf.gerar(template, destino, dados);
        confirmar(gerado.equals(destino.toAbsolutePath()), "destino retornado pelo gerador");
        confirmar(Arrays.equals(ContratoPdf.gerar(template, dados), Files.readAllBytes(destino)),
                "geração dos mesmos bytes em memória");
        String pdf = Files.readString(destino, StandardCharsets.US_ASCII);
        confirmar(pdf.startsWith("%PDF-1.4\n"), "cabeçalho do PDF");
        confirmar(pdf.contains("/Count 2 /Kids"), "paginação do PDF");
        verificarReferencias(pdf);

        List<String> linhas = extrairTexto(pdf);
        confirmar(linhas.contains("TÍTULO"), "template UTF-8 com BOM e acentuação");
        confirmar(linhas.contains("Comprador: " + comprador), "substituição com acentos e caracteres especiais");
        confirmar(linhas.contains("X".repeat(75)) && linhas.contains("XXXXX"), "quebra de linha longa");
        confirmar(linhas.contains("Última linha"), "conteúdo da última página");
        confirmar(linhas.containsAll(List.of("Página 1 de 2", "Página 2 de 2")), "rodapés");

        rejeitar(template, destino, Map.of(), "comprador");
        Files.writeString(template, "{{ campo inválido }}");
        rejeitar(template, destino, dados, "Campo inválido");
        Files.writeString(template, "{{comprador}}");
        rejeitar(template, destino, Map.of("comprador", "🚗"), "Windows-1252");
        rejeitar(template, template, dados, "arquivos distintos");
        confirmar(Files.readString(destino, StandardCharsets.US_ASCII).equals(pdf), "preservação do PDF válido");
    }

    private static void verificarReferencias(String pdf) {
        int marcador = pdf.lastIndexOf("startxref\n");
        int inicio = Integer.parseInt(pdf.substring(marcador).lines().toList().get(1));
        List<String> referencias = pdf.substring(inicio, marcador).lines().toList();
        confirmar(referencias.get(0).equals("xref"), "posição da tabela de referências");
        int tamanho = Integer.parseInt(referencias.get(1).substring(2));
        for (int objeto = 1; objeto < tamanho; objeto++) {
            int posicao = Integer.parseInt(referencias.get(objeto + 2).substring(0, 10));
            confirmar(pdf.startsWith(objeto + " 0 obj\n", posicao), "offset do objeto " + objeto);
        }
    }

    private static List<String> extrairTexto(String pdf) {
        Matcher texto = Pattern.compile("<([a-f0-9]*)> Tj").matcher(pdf);
        List<String> linhas = new ArrayList<>();
        while (texto.find()) {
            byte[] bytes = HexFormat.of().parseHex(texto.group(1));
            linhas.add(new String(bytes, Charset.forName("windows-1252")));
        }
        return linhas;
    }

    private static void rejeitar(Path template, Path destino, Map<String, String> dados, String erro) throws IOException {
        try {
            ContratoPdf.gerar(template, destino, dados);
        } catch (IllegalArgumentException e) {
            confirmar(e.getMessage().contains(erro), "mensagem sobre " + erro);
            return;
        }
        throw new AssertionError("O gerador deveria ter rejeitado: " + erro);
    }

    private static void confirmar(boolean condicao, String descricao) {
        if (!condicao) {
            throw new AssertionError("PDF: falhou a verificação de " + descricao);
        }
    }
}
