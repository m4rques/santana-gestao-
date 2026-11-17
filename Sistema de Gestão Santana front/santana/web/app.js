
const CONFIG = { USE_MOCK: false, API: '/api' };

const $ = (s, r = document) => r.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const brl = n => Number(n).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
const IMG = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="240" height="140"><rect width="240" height="140" fill="#e5e7eb"/><path d="M40 90l20-30h100l25 30v20H40z" fill="#9ca3af"/><circle cx="75" cy="112" r="12" fill="#4b5563"/><circle cx="165" cy="112" r="12" fill="#4b5563"/></svg>');

function msg(el, text, tipo = 'err') { el.className = 'msg ' + tipo; el.textContent = text; }

function docOk(s) { 
  const d = s.replace(/\D/g, '');
  if (/^(\d)\1+$/.test(d)) return false;
  if (d.length === 14) return true;
  if (d.length !== 11) return false;
  for (let t = 9; t < 11; t++) {
    let x = 0; for (let i = 0; i < t; i++) x += d[i] * (t + 1 - i);
    if ((x * 10 % 11) % 10 != d[t]) return false;
  }
  return true;
}


function logout() { sessionStorage.clear(); location.href = 'index.html'; }

function shell(page) { // protege a página, monta o menu e encerra sessão após 30 min inativa (RNF03)
  if (!sessionStorage.token) { location.href = 'index.html'; return; }
  const links = [['veiculos.html', 'Veículos'], ['cadastro.html', 'Cadastros'], ['contrato.html', 'Contrato'], ['dashboard.html', 'Relatórios']];
  $('#top').innerHTML = `<strong>Sant'Ana</strong><nav aria-label="Principal">${links.map(([h, t]) =>
    `<a href="${h}"${h === page ? ' aria-current="page"' : ''}>${t}</a>`).join('')}</nav>
    <span>${esc(sessionStorage.nome)}</span><button class="sec" id="sair" type="button">Sair</button>`;
  $('#sair').onclick = logout;
  let t; const r = () => { clearTimeout(t); t = setTimeout(logout, 30 * 60 * 1000); };
  ['click', 'keydown', 'touchstart'].forEach(e => addEventListener(e, r)); r();
}


async function api(path, method = 'GET', body, tipo = 'json') {
  if (CONFIG.USE_MOCK) return mock(path, method, body);
  const r = await fetch(CONFIG.API + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + (sessionStorage.token || '') },
    body: body ? JSON.stringify(body) : undefined
  });
  if (r.ok && tipo === 'pdf') return r.blob();
  const d = await r.json().catch(() => ({}));
  if (r.status === 401 && !path.startsWith('/login')) logout();
  if (!r.ok) throw new Error(d.erro || 'Erro ' + r.status);
  return d;
}


function seedDb() {
  const v = (id, marca, modelo, ano, tipo, km, preco, status) => ({ id, chassi: 'CH' + String(id).padStart(6, '0'), marca, modelo, ano, tipo, km, preco, status });
  return {
    usuarios: [{ email: 'admin@santana.com', senha: 'Admin@123', nome: 'Administrador', perfil: 'Administrador' }],
    veiculos: [
      v(1, 'Toyota', 'Corolla', 2024, 'Novo', 0, 165000, 'Disponível'), v(2, 'Honda', 'Civic', 2022, 'Seminovo', 31000, 125000, 'Disponível'),
      v(3, 'Volkswagen', 'Polo', 2023, 'Seminovo', 18000, 89000, 'Reservado'), v(4, 'Fiat', 'Argo', 2024, 'Novo', 0, 82000, 'Disponível'),
      v(5, 'Chevrolet', 'Onix', 2021, 'Seminovo', 52000, 62000, 'Vendido'), v(6, 'Jeep', 'Compass', 2024, 'Novo', 0, 205000, 'Disponível'),
      v(7, 'Hyundai', 'HB20', 2022, 'Seminovo', 40000, 69000, 'Em manutenção'), v(8, 'Toyota', 'Hilux', 2023, 'Seminovo', 25000, 255000, 'Disponível')
    ],
    clientes: [{ codigo: 'CLI-0001', nome: 'Maria Souza', cpf: '52998224725', telefone: '(71) 99999-0000', email: 'maria@email.com', endereco: 'Rua das Flores, 100, Salvador/BA' }],
    vendas: [{ id: 1, data: '2026-10-15', valor: 62000, pagamento: 'Financiamento em 48x', cliente: { nome: 'Maria Souza', cpf: '52998224725', endereco: 'Rua das Flores, 100, Salvador/BA' }, veiculo: { marca: 'Chevrolet', modelo: 'Onix', ano: 2021, chassi: 'CH000005' } }]
  };
}

async function mock(path, method, body) {
  const db = JSON.parse(localStorage.sa_db || 'null') || seedDb();
  const save = () => localStorage.sa_db = JSON.stringify(db);
  const [p, qs] = path.split('?'); const q = Object.fromEntries(new URLSearchParams(qs || ''));
  const n = s => (s || '').toLowerCase();
  if (p === '/login') {
    const u = db.usuarios.find(u => u.email === body.email && u.senha === body.senha);
    if (!u) throw new Error('E-mail ou senha incorretos.');
    return { token: 'mock', nome: u.nome, perfil: u.perfil };
  }
  if (p === '/veiculos') {
    if (method === 'POST') {
      if (db.veiculos.some(x => x.chassi === body.chassi)) throw new Error('Já existe um veículo com este chassi.');
      const novo = { ...body, id: Date.now(), status: 'Disponível' }; db.veiculos.push(novo); save(); return novo;
    }
    return db.veiculos.filter(x => (!q.marca || n(x.marca).includes(n(q.marca))) && (!q.modelo || n(x.modelo).includes(n(q.modelo)))
      && (!q.precoMax || x.preco <= +q.precoMax) && (!q.status || x.status === q.status));
  }
  if (p === '/clientes') {
    if (method === 'POST') {
      if (db.clientes.some(c => c.cpf === body.cpf)) throw new Error('Cliente já cadastrado com este CPF/CNPJ.');
      const novo = { ...body, codigo: 'CLI-' + String(db.clientes.length + 1).padStart(4, '0') }; db.clientes.push(novo); save(); return novo;
    }
    return db.clientes;
  }
  if (p.startsWith('/vendas/')) {
    const x = db.vendas.find(x => x.id == p.split('/')[2]);
    if (!x) throw new Error('Venda não encontrada.');
    return x;
  }
  if (p === '/dashboard') {
    const vendas = [3, 4, 2, 5, 6, 4, 7, 5, 6, 8, 5, 9], ticket = [82, 90, 75, 88, 95, 80, 92, 85, 78, 99, 84, 90];
    const k = Math.min(+q.meses || 12, 12), now = new Date();
    const meses = vendas.map((c, i) => {
      const d = new Date(now.getFullYear(), now.getMonth() - 11 + i, 1);
      return { mes: d.toLocaleDateString('pt-BR', { month: 'short', year: '2-digit' }), vendas: c, receita: c * ticket[i] * 1000 };
    }).slice(-k);
    return { meses, maisVendidos: [{ modelo: 'Corolla', qtd: 14 }, { modelo: 'Onix', qtd: 11 }, { modelo: 'Compass', qtd: 9 }, { modelo: 'Argo', qtd: 7 }, { modelo: 'Hilux', qtd: 5 }] };
  }
  throw new Error('Rota não encontrada: ' + p);
}
