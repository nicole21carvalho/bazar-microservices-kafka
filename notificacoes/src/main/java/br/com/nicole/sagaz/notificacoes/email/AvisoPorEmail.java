package br.com.nicole.sagaz.notificacoes.email;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import br.com.nicole.sagaz.comum.eventos.Evento.PedidoCancelado;
import br.com.nicole.sagaz.comum.eventos.Evento.PedidoConfirmado;

@Service
public class AvisoPorEmail {

    private static final Logger log = LoggerFactory.getLogger(AvisoPorEmail.class);

    private final JavaMailSender email;
    private final JdbcTemplate jdbc;
    private final String remetente;

    public AvisoPorEmail(
            JavaMailSender email, JdbcTemplate jdbc, @Value("${sagaz.notificacoes.remetente}") String remetente) {
        this.email = email;
        this.jdbc = jdbc;
        this.remetente = remetente;
    }

    public void confirmado(PedidoConfirmado pedido) {
        enviar(pedido.pedidoId(), "confirmado", pedido.clienteEmail(),
                "Pedido confirmado ✅",
                "Seu pedido " + curto(pedido.pedidoId()) + " foi confirmado.\n"
                        + "Total pago: " + reais(pedido.total()) + ".\n\nObrigado pela compra!");
    }

    public void cancelado(PedidoCancelado pedido) {
        enviar(pedido.pedidoId(), "cancelado", pedido.clienteEmail(),
                "Pedido não concluído",
                "Não foi possível concluir o pedido " + curto(pedido.pedidoId()) + ".\n"
                        + "Motivo: " + pedido.motivo() + ".\n\nNenhum valor foi cobrado.");
    }

    /**
     * O SMTP nao participa da transacao do banco. Se o e-mail sair e o commit
     * falhar depois, o evento volta e o e-mail pode sair duas vezes; o contrario
     * (perder o aviso) nunca acontece. Para aviso ao cliente, e a troca certa.
     */
    private void enviar(UUID pedidoId, String tipo, String para, String assunto, String texto) {
        var mensagem = new SimpleMailMessage();
        mensagem.setFrom(remetente);
        mensagem.setTo(para);
        mensagem.setSubject(assunto);
        mensagem.setText(texto);
        email.send(mensagem);
        jdbc.update("INSERT INTO notificacao (pedido_id, tipo, enviada_em) VALUES (?, ?, now())", pedidoId, tipo);
        // Sem o e-mail no log: endereco de cliente e dado pessoal (LGPD).
        log.info("Pedido {}: aviso de pedido {} enviado", pedidoId, tipo);
    }

    private static String curto(UUID id) {
        return "#" + id.toString().substring(0, 8);
    }

    private static String reais(BigDecimal valor) {
        return NumberFormat.getCurrencyInstance(Locale.of("pt", "BR")).format(valor);
    }
}
