// Ponte do Admin no site (o mesmo window.Aoi que o Kotlin dá na app), com o supabase-js e a sessão do site.
// Na app esta página corre numa WebView com a ponte do Kotlin e este ficheiro não é carregado.
(function(){
  const SUPABASE_URL = 'https://lmwsecuiylqkpeabckdv.supabase.co';
  const SUPABASE_ANON_KEY = 'sb_publishable_PEXBRGTlg78gVnI1SbLY-g_rWq9wAHR';
  const sb = window.supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY);
  let uid = null;

  // Refeições antigas (tabela admin_meals, partilhada) passam para a chave 'meals' da admin_data
  async function legacyMeals(){
    const { data, error } = await sb.from('admin_meals').select('day, at_home');
    if(error || !data) return {};
    const out = {};
    data.forEach(r => out[r.day] = r.at_home);
    return out;
  }

  async function fetchRepos(){
    const token = localStorage.getItem('__ghToken') || '';
    const result = {};
    try{
      const repos = [];
      for(let page = 1; page <= 5; page++){
        const r = await fetch(`https://api.github.com/user/repos?per_page=100&sort=updated&affiliation=owner&page=${page}`, {
          headers: { Accept:'application/vnd.github+json', Authorization:`Bearer ${token}`, 'X-GitHub-Api-Version':'2022-11-28' }
        });
        if(r.status !== 200){ result.error = r.status === 401 ? 'Token inválido ou expirado' : `Erro do GitHub (${r.status})`; break; }
        const items = await r.json();
        items.forEach(x => repos.push({ name:x.name, desc:x.description || '', priv:x.private, url:x.html_url, user:x.owner.login }));
        if(items.length < 100) break;
      }
      if(!result.error) result.repos = repos;
    }catch(e){
      result.error = 'Sem ligação ao GitHub';
    }
    window.onRepos && window.onRepos(JSON.stringify(result));
  }

  const USER_ERRORS = {
    not_admin: 'Só um admin pode fazer isto.',
    target_is_admin: 'Não dá para mudar a palavra-passe de outro admin.',
    cannot_change_own_password: 'A tua palavra-passe muda-se em Profile.',
    cannot_remove_own_admin: 'Não podes tirar o admin a ti próprio.',
    password_too_short: 'A palavra-passe tem de ter pelo menos 6 caracteres.',
    password_rejected: 'O Supabase recusou esta palavra-passe.',
    user_not_found: 'Esta conta já não existe.',
    42501: 'Só um admin com sessão iniciada pode fazer isto.',
  };
  const userError = code => new Error(USER_ERRORS[code] || `Erro (${code})`);
  const rpcError = e => userError(Object.keys(USER_ERRORS).find(c => e.message.includes(c)) || e.code);

  window.Aoi = {
    userId: () => uid,

    async ready(){
      const { data:{ session } } = await sb.auth.getSession();
      if(!session) return window.onCloudFailed();
      uid = session.user.id;
      const { data, error } = await sb.from('admin_data').select('key, value').eq('user_id', uid);
      if(error) return window.onCloudFailed();
      const obj = {};
      data.forEach(r => obj[r.key] = r.value);
      if(!('meals' in obj)) obj.meals = await legacyMeals();
      window.onCloud(uid, JSON.stringify(obj));
    },

    async save(key, json, stamp){
      if(!uid) return window.onSaveFailed();
      const { error } = await sb.from('admin_data')
        .upsert({ user_id:uid, key, value:JSON.parse(json) }, { onConflict:'user_id,key' });
      error ? window.onSaveFailed() : window.onSaved(key, stamp);
    },

    close(){
      if(window.parent !== window) window.parent.postMessage({ aoiAdmin:'close' }, location.origin);
      else location.href = '/';
    },

    openUrl(url){ window.open(url, '_blank', 'noopener'); },

    // Token do GitHub só neste browser, como na app fica só no telemóvel
    hasGithubToken: () => !!localStorage.getItem('__ghToken'),
    setGithubToken(token){ token ? localStorage.setItem('__ghToken', token.trim()) : localStorage.removeItem('__ghToken'); },
    fetchRepos,

    users: {
      async list(search){
        const { data, error } = await sb.rpc('admin_list_users', { search: search || null });
        if(error) throw rpcError(error);
        return data;
      },
      async setAdmin(userId, makeAdmin){
        const { error } = await sb.rpc('admin_set_admin', { target_user_id:userId, make_admin:makeAdmin });
        if(error) throw rpcError(error);
      },
      async setPassword(userId, newPassword){
        const { data, error } = await sb.functions.invoke('admin-set-password', { body:{ user_id:userId, new_password:newPassword } });
        if(error){
          let code = 'http';
          try{ code = (await error.context.json()).error || code; }catch(e){}
          throw userError(code);
        }
        if(data && data.error) throw userError(data.error);
      },
    },
  };
})();
