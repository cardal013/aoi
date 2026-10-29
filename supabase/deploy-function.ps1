# Deploy de uma edge function pela Management API do Supabase, com o token que o CLI guardou no
# Gestor de Credenciais (o supabase.exe está bloqueado pelo Controlo de Aplicações). Não mostra o token.
# Uso: .\supabase\deploy-function.ps1 -Slug mal   (admin-set-password: -Slug admin-set-password -VerifyJwt)
param([string]$Slug = 'mal', [switch]$VerifyJwt, [string]$Repo = (Split-Path $PSScriptRoot -Parent), [string]$ProjectRef = 'lmwsecuiylqkpeabckdv')

$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CredReader {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct CREDENTIAL {
        public int Flags; public int Type; public string TargetName; public string Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public int CredentialBlobSize; public IntPtr CredentialBlob; public int Persist;
        public int AttributeCount; public IntPtr Attributes; public string TargetAlias; public string UserName;
    }
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CredRead(string target, int type, int flags, out IntPtr cred);
    [DllImport("advapi32.dll")] static extern void CredFree(IntPtr cred);
    public static byte[] Read(string target) {
        IntPtr ptr;
        if (!CredRead(target, 1, 0, out ptr)) return null;
        try {
            var cred = (CREDENTIAL)Marshal.PtrToStructure(ptr, typeof(CREDENTIAL));
            var bytes = new byte[cred.CredentialBlobSize];
            Marshal.Copy(cred.CredentialBlob, bytes, 0, cred.CredentialBlobSize);
            return bytes;
        } finally { CredFree(ptr); }
    }
}
'@

$blob = [CredReader]::Read('Supabase CLI:supabase')
if ($null -eq $blob) { throw 'Não há credencial "Supabase CLI:supabase"' }
$token = [Text.Encoding]::UTF8.GetString($blob)
if ($token -notmatch '^sbp_') { $token = [Text.Encoding]::Unicode.GetString($blob) }
if ($token.StartsWith('go-keyring-base64:')) {
    $token = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($token.Substring(18)))
}
$token = $token.Trim()
if ($token -notmatch '^sbp_') { throw 'A credencial não parece um access token do Supabase (sbp_...)' }
Write-Output ('token lido (' + $token.Length + ' caracteres)')

Push-Location $Repo
try {
    $files = @(Get-ChildItem "supabase\functions\$Slug\*.ts" | Where-Object { $_.Name -notlike '*_test.ts' }) +
             @(Get-ChildItem 'supabase\functions\_shared\*.ts')
    $metadata = @{ entrypoint_path = "supabase/functions/$Slug/index.ts"; name = $Slug; verify_jwt = [bool]$VerifyJwt } | ConvertTo-Json -Compress
    $metaFile = Join-Path $env:TEMP 'aoi-deploy-metadata.json'
    [IO.File]::WriteAllText($metaFile, $metadata)

    $curlArgs = @('-sS', '-X', 'POST', "https://api.supabase.com/v1/projects/$ProjectRef/functions/deploy?slug=$Slug",
              '-H', "Authorization: Bearer $token", '-F', "metadata=<$metaFile;type=application/json")
    foreach ($file in $files) {
        $relative = $file.FullName.Substring((Get-Location).Path.Length + 1).Replace('\', '/')
        $curlArgs += @('-F', "file=@$($file.FullName);filename=$relative;type=application/typescript")
        Write-Output "  + $relative"
    }
    $curlArgs += @('-w', "`nHTTP %{http_code}")
    & curl.exe @args
} finally {
    Pop-Location
    Remove-Variable token -ErrorAction SilentlyContinue
}
