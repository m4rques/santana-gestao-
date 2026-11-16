// Usuário logado (guardado em memória, por token).
class Sessao {
    static final String ADMIN = "Administrador", GERENTE = "Gerente de Vendas",
                        VENDEDOR = "Vendedor", RECEPCIONISTA = "Recepcionista";

    final long id;
    final String nome, perfil;
    volatile long ultimoAcesso = System.currentTimeMillis();

    Sessao(long id, String nome, String perfil) {
        this.id = id;
        this.nome = nome;
        this.perfil = perfil;
    }
}
