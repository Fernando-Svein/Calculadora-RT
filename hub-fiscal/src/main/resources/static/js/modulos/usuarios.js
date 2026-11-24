/* Sistema: administração de usuários do hub (somente administradores). */
'use strict';

(() => {
  const { esc, api, comp } = Hub;

  const dataHora = (v) => (v ? new Date(v).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : 'Nunca acessou');

  function mostrarSenha(titulo, login, senha) {
    const c = Hub.modal(`<h2>${esc(titulo)}</h2>
      <p>Repasse ao usuário <strong>${esc(login)}</strong> a senha provisória abaixo. Ela aparece <strong>só agora</strong>
        e precisará ser trocada no primeiro acesso.</p>
      <div class="senha-provisoria">${esc(senha)}</div>
      <div class="acoes"><button class="btn" data-copiar>Copiar senha</button><button class="btn btn-sec" data-ok>Concluir</button></div>`);
    c.querySelector('[data-copiar]').onclick = async () => {
      try { await navigator.clipboard.writeText(senha); Hub.toast('Senha copiada.'); } catch (e) { Hub.toast('Selecione e copie a senha manualmente.', 'erro'); }
    };
    c.querySelector('[data-ok]').onclick = Hub.fecharModal;
  }

  function formulario(u, aoSalvar) {
    const novo = !u;
    const c = Hub.modal(`<h2>${novo ? 'Novo usuário' : 'Editar usuário'}</h2>
      <form data-f class="grade" style="gap:12px;min-width:min(420px,80vw)">
        <div class="campo"><label>Nome *</label><input name="nome" value="${esc(u ? u.nome : '')}" required maxlength="100"></div>
        <div class="campo"><label>Usuário (login) *</label><input name="login" value="${esc(u ? u.login : '')}" ${novo ? 'required' : 'disabled'}
          autocapitalize="none" spellcheck="false" placeholder="ex.: maria.souza">
          ${novo ? '<span class="ajuda">Letras sem acento, números, ponto, hífen ou sublinhado.</span>' : ''}</div>
        <div class="campo"><label>Perfil</label><select name="perfil">
          <option value="USUARIO" ${u && u.perfil === 'USUARIO' ? 'selected' : ''}>Usuário – usa todas as ferramentas</option>
          <option value="ADMIN" ${u && u.perfil === 'ADMIN' ? 'selected' : ''}>Administrador – também gerencia usuários</option></select></div>
        ${novo ? '' : `<label class="check"><input type="checkbox" name="ativo" ${u.ativo ? 'checked' : ''}> Acesso liberado</label>`}
        <div data-erro></div>
        <div class="acoes"><button class="btn" type="submit">${novo ? 'Criar usuário' : 'Salvar'}</button>
          <button class="btn btn-sec" type="button" data-cancelar>Cancelar</button></div>
      </form>`);
    const f = c.querySelector('[data-f]');
    c.querySelector('[data-cancelar]').onclick = Hub.fecharModal;
    (novo ? f.nome : f.nome).focus();
    f.onsubmit = async (e) => {
      e.preventDefault();
      const erro = f.querySelector('[data-erro]');
      const r = novo
        ? await api.post('/admin/usuarios', { nome: f.nome.value, login: f.login.value, perfil: f.perfil.value })
        : await api.put(`/admin/usuarios/${u.id}`, { nome: f.nome.value, perfil: f.perfil.value, ativo: f.ativo.checked });
      if (!r.ok) { erro.innerHTML = Hub.problema(r, novo ? 'Usuário não criado' : 'Alteração não salva'); return; }
      if (novo) mostrarSenha('Usuário criado', r.data.login, r.data.senhaProvisoria);
      else { Hub.fecharModal(); Hub.toast('Usuário atualizado.'); }
      aoSalvar();
    };
  }

  Hub.registrar({
    rota: 'usuarios',
    titulo: 'Usuários',
    grupo: 'Sistema',
    perfil: 'ADMIN',
    ordem: 3,
    descricao: 'Cadastro de quem acessa o hub: criar usuários, perfis, bloquear acesso e redefinir senhas.',
    async render(el) {
      el.innerHTML = comp.cabecalho('Usuários', 'Quem pode acessar o hub. Novos usuários recebem uma senha provisória, trocada no primeiro acesso. ' +
        'Desativar um usuário encerra o acesso dele imediatamente.') +
        `<div class="card"><div class="card-cab"><h3>Usuários cadastrados</h3>
          <button class="btn" data-novo>${Hub.icone('mais')} Novo usuário</button></div><div data-lista></div></div>
        <div class="info-caixa">Segurança: senhas guardadas com criptografia (BCrypt); 5 tentativas erradas bloqueiam o usuário por 15 minutos;
          a sessão expira após 8 horas sem uso. O cadastro fica no arquivo <code>dados\\hub-usuarios.db</code> do servidor – inclua-o no backup.</div>`;
      const lista = el.querySelector('[data-lista]');

      async function carregar() {
        Hub.carregando(lista, 'Carregando usuários...');
        const r = await api.get('/admin/usuarios');
        if (!r.ok) { lista.innerHTML = Hub.problema(r); return; }
        const eu = Hub.usuario.id;
        lista.innerHTML = `<div class="tabela-wrap" style="max-height:none"><table class="tab"><thead><tr>
            <th>Nome</th><th>Usuário</th><th>Perfil</th><th>Situação</th><th>Último acesso</th><th></th></tr></thead><tbody>
          ${r.data.map((u) => `<tr data-id="${u.id}">
            <td><strong>${esc(u.nome)}</strong>${u.id === eu ? ' <span class="etq etq-azul">você</span>' : ''}</td>
            <td class="mono">${esc(u.login)}</td>
            <td>${u.perfil === 'ADMIN' ? '<span class="etq etq-azul">Administrador</span>' : 'Usuário'}</td>
            <td>${!u.ativo ? '<span class="etq st-DIVERGENTE">Desativado</span>'
              : u.trocarSenha ? '<span class="etq st-REVISAR">Aguardando 1º acesso</span>' : '<span class="etq st-OK">Ativo</span>'}</td>
            <td>${esc(dataHora(u.ultimoAcesso))}</td>
            <td style="text-align:right;white-space:nowrap">
              <button class="btn btn-sec btn-peq" data-acao="editar">Editar</button>
              <button class="btn btn-sec btn-peq" data-acao="senha">Redefinir senha</button>
              ${u.id === eu ? '' : '<button class="btn btn-perigo btn-peq" data-acao="excluir">Excluir</button>'}
            </td></tr>`).join('')}
          </tbody></table></div>`;
        lista.querySelectorAll('[data-acao]').forEach((b) => {
          const u = r.data.find((x) => String(x.id) === b.closest('tr').dataset.id);
          b.onclick = async () => {
            if (b.dataset.acao === 'editar') formulario(u, carregar);
            if (b.dataset.acao === 'senha') {
              if (!confirm(`Gerar uma nova senha provisória para ${u.nome}? A senha atual deixa de funcionar.`)) return;
              const s = await api.post(`/admin/usuarios/${u.id}/redefinir-senha`);
              if (!s.ok) { Hub.modal(Hub.problema(s, 'Senha não redefinida')); return; }
              mostrarSenha('Senha redefinida', u.login, s.data.senhaProvisoria);
              carregar();
            }
            if (b.dataset.acao === 'excluir') {
              if (!confirm(`Excluir o usuário ${u.nome} (${u.login})? Prefira "Editar → desativar" se quiser apenas bloquear o acesso.`)) return;
              const x = await api.del(`/admin/usuarios/${u.id}`);
              if (!x.ok) { Hub.modal(Hub.problema(x, 'Usuário não excluído')); return; }
              Hub.toast('Usuário excluído.');
              carregar();
            }
          };
        });
      }
      el.querySelector('[data-novo]').onclick = () => formulario(null, carregar);
      carregar();
    },
  });
})();
