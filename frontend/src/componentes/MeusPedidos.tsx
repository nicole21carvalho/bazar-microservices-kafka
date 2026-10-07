import type { Pedido } from "../api/api";
import { reais } from "../carrinho/carrinho";

const ROTULO = {
  PENDENTE: { texto: "Recebido", cor: "bg-alerta/10 text-alerta" },
  AGUARDANDO_PAGAMENTO: { texto: "Pagando", cor: "bg-alerta/10 text-alerta" },
  CONFIRMADO: { texto: "Confirmado", cor: "bg-sucesso/10 text-sucesso" },
  CANCELADO: { texto: "Cancelado", cor: "bg-perigo/10 text-perigo" },
} as const;

export function MeusPedidos({ pedidos, aoAbrir }: { pedidos: Pedido[]; aoAbrir: (id: string) => void }) {
  if (pedidos.length === 0) return null;
  return (
    <section aria-labelledby="titulo-meus-pedidos" className="rounded-2xl border border-borda bg-painel p-5">
      <h2 id="titulo-meus-pedidos" className="font-semibold">
        Meus pedidos
      </h2>
      <ul className="mt-3 divide-y divide-borda">
        {pedidos.map((p) => (
          <li key={p.id}>
            <button
              type="button"
              onClick={() => aoAbrir(p.id)}
              className="flex w-full items-center justify-between gap-2 py-2 text-left text-sm hover:text-destaque-forte"
            >
              <span className="font-mono text-xs">#{p.id.slice(0, 8)}</span>
              <span className={`rounded-md px-2 py-0.5 text-xs font-medium ${ROTULO[p.status].cor}`}>
                {ROTULO[p.status].texto}
              </span>
              <span className="w-24 text-right">{p.total !== null ? reais.format(p.total) : "—"}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
