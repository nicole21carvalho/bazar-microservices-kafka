import type { Pedido, StatusPedido } from "../api/api";
import { reais } from "../carrinho/carrinho";

const ETAPAS: { status: StatusPedido; titulo: string; descricao: string }[] = [
  { status: "PENDENTE", titulo: "Pedido recebido", descricao: "Enviado ao estoque" },
  { status: "AGUARDANDO_PAGAMENTO", titulo: "Itens separados", descricao: "Cobrando o pagamento" },
  { status: "CONFIRMADO", titulo: "Pedido confirmado", descricao: "Aviso enviado por e-mail" },
];

const ORDEM: Record<StatusPedido, number> = { PENDENTE: 0, AGUARDANDO_PAGAMENTO: 1, CONFIRMADO: 2, CANCELADO: -1 };

/** Linha do tempo da saga: cada etapa acende quando o evento correspondente chega. */
export function StatusDoPedido({ pedido }: { pedido: Pedido }) {
  const cancelado = pedido.status === "CANCELADO";
  const atual = ORDEM[pedido.status];

  return (
    <section aria-label="Andamento do pedido" className="rounded-2xl border border-borda bg-painel p-5">
      <div className="flex items-baseline justify-between gap-4">
        <h2 className="font-semibold">Pedido #{pedido.id.slice(0, 8)}</h2>
        {pedido.total !== null && <span className="font-semibold">{reais.format(pedido.total)}</span>}
      </div>

      {cancelado ? (
        <div role="status" className="mt-4 rounded-xl bg-perigo/10 px-4 py-3 text-sm text-perigo">
          <p className="font-medium">Pedido não concluído</p>
          <p className="mt-1">{pedido.motivo}</p>
          <p className="mt-1 text-perigo/80">Nenhum valor foi cobrado e os itens voltaram ao estoque.</p>
        </div>
      ) : (
        <ol className="mt-5 space-y-4">
          {ETAPAS.map((etapa, i) => {
            const feita = atual >= i;
            const andando = atual === i && pedido.status !== "CONFIRMADO";
            return (
              <li key={etapa.status} className="flex items-start gap-3">
                <span
                  aria-hidden="true"
                  className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-bold ${
                    feita ? "bg-sucesso text-white" : "border-2 border-borda text-suave"
                  } ${andando ? "animate-pulse" : ""}`}
                >
                  {feita ? "✓" : i + 1}
                </span>
                <div>
                  <p className={`text-sm font-medium ${feita ? "" : "text-suave"}`}>
                    {etapa.titulo}
                    <span className="sr-only">{feita ? " (concluído)" : " (aguardando)"}</span>
                  </p>
                  <p className="text-xs text-suave">{etapa.descricao}</p>
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
