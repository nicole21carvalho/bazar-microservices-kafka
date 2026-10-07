package br.com.nicole.vitrine.estoque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import br.com.nicole.vitrine.comum.teste.Containers;
import br.com.nicole.vitrine.comum.teste.Mensageiro;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import br.com.nicole.vitrine.comum.eventos.Evento;
import br.com.nicole.vitrine.comum.eventos.Evento.EstoqueRecusado;
import br.com.nicole.vitrine.comum.eventos.Evento.EstoqueReservado;
import br.com.nicole.vitrine.comum.eventos.Evento.ItemPedido;
import br.com.nicole.vitrine.comum.eventos.Evento.PedidoCancelado;
import br.com.nicole.vitrine.comum.eventos.Evento.PedidoCriado;
import br.com.nicole.vitrine.comum.eventos.LeitorDeEventos;
import br.com.nicole.vitrine.comum.eventos.Topicos;
import br.com.nicole.vitrine.comum.outbox.LimpezaDeDados;
import br.com.nicole.vitrine.estoque.reserva.ReservaService;

@SpringBootTest(properties = {"DB_SENHA_ESTOQUE=teste", "vitrine.outbox.intervalo=100ms"})
@Import(Containers.class)
class ReservaIntegracaoTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ReservaService reservas;
    @Autowired LimpezaDeDados limpeza;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsumerFactory<String, String> consumidores;
    @Autowired LeitorDeEventos leitor;

    Mensageiro mensageiro;

    @BeforeEach
    void preparar() {
        mensageiro = new Mensageiro(kafka, consumidores, leitor);
    }

    private static PedidoCriado pedido(ItemPedido... itens) {
        return new PedidoCriado(UUID.randomUUID(), List.of(itens), Instant.now());
    }

    private int disponivel(String produto) {
        return jdbc.queryForObject("SELECT disponivel FROM produto WHERE id = ?", Integer.class, produto);
    }

    @Test
    void pedido_com_estoque_reserva_os_itens_e_publica_o_total_com_os_precos() throws Exception {
        int cafeAntes = disponivel("cafe-especial");
        var pedido = pedido(new ItemPedido("cafe-especial", 2), new ItemPedido("caneca-ceramica", 1));

        mensageiro.publicar(Topicos.PEDIDOS, pedido, UUID.randomUUID());

        List<Evento> saida = mensageiro.eventos(Topicos.ESTOQUE, pedido.pedidoId(), 1);
        assertThat(saida).singleElement().isInstanceOfSatisfying(EstoqueReservado.class,
                e -> assertThat(e.total()).isEqualByComparingTo("125.70")); // 2 x 42,90 + 39,90
        assertThat(disponivel("cafe-especial")).isEqualTo(cafeAntes - 2);
    }

    @Test
    void sem_estoque_de_um_item_recusa_o_pedido_inteiro_sem_mexer_nos_outros() throws Exception {
        int cafeAntes = disponivel("cafe-especial");
        var pedido = pedido(new ItemPedido("cafe-especial", 1), new ItemPedido("luminaria-mesa", 50));

        mensageiro.publicar(Topicos.PEDIDOS, pedido, UUID.randomUUID());

        assertThat(mensageiro.eventos(Topicos.ESTOQUE, pedido.pedidoId(), 1))
                .singleElement().isInstanceOfSatisfying(EstoqueRecusado.class,
                        e -> assertThat(e.motivo()).contains("Luminária"));
        assertThat(disponivel("cafe-especial")).isEqualTo(cafeAntes);
    }

    @Test
    void mensagem_repetida_com_o_mesmo_id_reserva_uma_vez_so() throws Exception {
        int canecaAntes = disponivel("caneca-ceramica");
        var pedido = pedido(new ItemPedido("caneca-ceramica", 3));
        var mesmoId = UUID.randomUUID();

        mensageiro.publicar(Topicos.PEDIDOS, pedido, mesmoId);
        mensageiro.publicar(Topicos.PEDIDOS, pedido, mesmoId);

        assertThat(mensageiro.eventos(Topicos.ESTOQUE, pedido.pedidoId(), 1)).hasSize(1);
        assertThat(disponivel("caneca-ceramica")).isEqualTo(canecaAntes - 3);
    }

    @Test
    void pedido_cancelado_devolve_os_itens_ao_estoque() throws Exception {
        int mouseAntes = disponivel("mouse-sem-fio");
        var pedido = pedido(new ItemPedido("mouse-sem-fio", 4));
        mensageiro.publicar(Topicos.PEDIDOS, pedido, UUID.randomUUID());
        mensageiro.eventos(Topicos.ESTOQUE, pedido.pedidoId(), 1);
        assertThat(disponivel("mouse-sem-fio")).isEqualTo(mouseAntes - 4);

        var cancelado = new PedidoCancelado(pedido.pedidoId(), "ana@exemplo.com", "Pagamento recusado");
        mensageiro.publicar(Topicos.PEDIDOS, cancelado, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(15)).until(() -> disponivel("mouse-sem-fio") == mouseAntes);
    }

    @Test
    void dois_pedidos_ao_mesmo_tempo_pelo_ultimo_estoque_so_um_leva() throws Exception {
        jdbc.update("UPDATE produto SET disponivel = 2 WHERE id = 'luminaria-mesa'");
        var largada = new CyclicBarrier(2);
        Runnable comprarDuas = () -> {
            try {
                largada.await();
                reservas.reservar(pedido(new ItemPedido("luminaria-mesa", 2)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        CompletableFuture.allOf(CompletableFuture.runAsync(comprarDuas), CompletableFuture.runAsync(comprarDuas)).get();

        // Sem o FOR UPDATE, as duas threads leriam "2 disponiveis" e as duas
        // reservariam: o CHECK do banco ou o estoque negativo acusariam.
        assertThat(disponivel("luminaria-mesa")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reserva_item WHERE produto_id = 'luminaria-mesa'", Integer.class)).isEqualTo(1);
    }

    @Test
    void mensagem_invalida_vai_para_o_dead_letter_topic_sem_travar_a_fila() throws Exception {
        String chave = UUID.randomUUID().toString();
        mensageiro.publicarCru(Topicos.PEDIDOS, chave, "PedidoCriado", UUID.randomUUID(), "{isto nao e json");
        var depois = pedido(new ItemPedido("cafe-especial", 1));
        mensageiro.publicar(Topicos.PEDIDOS, depois, UUID.randomUUID());

        assertThat(mensageiro.esperar(Topicos.PEDIDOS + "-dlt", chave, 1)).hasSize(1);
        // A mensagem seguinte foi processada normalmente.
        assertThat(mensageiro.eventos(Topicos.ESTOQUE, depois.pedidoId(), 1))
                .singleElement().isInstanceOf(EstoqueReservado.class);
    }

    @Test
    void limpeza_apaga_so_o_que_ja_foi_publicado_ha_mais_de_7_dias() {
        UUID antigoPublicado = UUID.randomUUID();
        UUID antigoPendente = UUID.randomUUID();
        UUID recentePublicado = UUID.randomUUID();
        String inserir = """
                INSERT INTO outbox (id, topico, chave, tipo, payload, criado_em, publicado_em)
                VALUES (?, 'vitrine.estoque', 'k', 'EstoqueReservado', '{}'::jsonb,
                        now() - make_interval(days => ?), now() - make_interval(days => ?))
                """;
        jdbc.update(inserir, antigoPublicado, 30, 30);
        jdbc.update(inserir, recentePublicado, 1, 1);
        jdbc.update("""
                INSERT INTO outbox (id, topico, chave, tipo, payload, criado_em)
                VALUES (?, 'vitrine.estoque', 'k', 'EstoqueReservado', '{}'::jsonb, now() - interval '30 days')
                """, antigoPendente);

        limpeza.limpar();

        assertThat(existe(antigoPublicado)).isFalse();
        // Evento que ainda nao saiu nunca e apagado, por mais antigo que seja.
        assertThat(existe(antigoPendente)).isTrue();
        assertThat(existe(recentePublicado)).isTrue();
        jdbc.update("DELETE FROM outbox WHERE id = ?", antigoPendente);
    }

    private boolean existe(UUID idOutbox) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE id = ?", Integer.class, idOutbox) == 1;
    }
}
