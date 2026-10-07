import type { Produto } from "../api/api";
import { type Carrinho, reais } from "../carrinho/carrinho";

type Props = { produtos: Produto[]; carrinho: Carrinho; aoAdicionar: (p: Produto) => void };

export function Catalogo({ produtos, carrinho, aoAdicionar }: Props) {
  return (
    <section aria-labelledby="titulo-catalogo">
      <h2 id="titulo-catalogo" className="text-lg font-semibold">
        Produtos
      </h2>
      <ul className="mt-4 grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {produtos.map((produto) => {
          const noCarrinho = carrinho[produto.id] ?? 0;
          const esgotado = produto.disponivel === 0;
          const limite = noCarrinho >= produto.disponivel;
          return (
            <li key={produto.id} className="flex flex-col rounded-2xl border border-borda bg-painel p-4">
              <p className="font-medium">{produto.nome}</p>
              <p className="mt-1 text-lg font-semibold text-destaque-forte">{reais.format(produto.preco)}</p>
              <p className={`mt-1 text-xs ${produto.disponivel <= 3 ? "text-alerta" : "text-suave"}`}>
                {esgotado ? "Esgotado" : `${produto.disponivel} em estoque`}
              </p>
              <button
                type="button"
                onClick={() => aoAdicionar(produto)}
                disabled={esgotado || limite}
                aria-label={`Adicionar ${produto.nome} ao carrinho`}
                className="mt-4 rounded-xl bg-destaque px-3 py-2 text-sm font-medium text-white transition hover:bg-destaque-forte disabled:cursor-not-allowed disabled:opacity-40"
              >
                {noCarrinho > 0 ? `Adicionar mais (${noCarrinho})` : "Adicionar"}
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
