// Valores comuns a todos os ficheiros da extensão (background, overlay, ponte com o site e popup).
// O URL e a chave do Supabase são os mesmos do site (index.html); a chave é pública (publishable).
// "var" e não "const": no localhost este ficheiro entra duas vezes na mesma página (ponte e overlay) e um
// const repetido dava erro.

var AOI_SUPABASE_URL = 'https://lmwsecuiylqkpeabckdv.supabase.co';
var AOI_SUPABASE_KEY = 'sb_publishable_PEXBRGTlg78gVnI1SbLY-g_rWq9wAHR';
var AOI_SITE_URL = 'https://aoi-mangas.vercel.app/';
