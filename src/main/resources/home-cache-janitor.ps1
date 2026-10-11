param(
    [Parameter(Mandatory = $true)]
    [long] $WaitPid,
    [Parameter(Mandatory = $true)]
    [string] $Home
)

$name = Split-Path -Leaf $Home
if ($name -ne '.robotmc-installer') {
    exit 1
}
$resolved = [System.IO.Path]::GetFullPath($Home)
$user = [System.IO.Path]::GetFullPath($env:USERPROFILE)
if ($resolved -eq $user -or $resolved -eq [System.IO.Path]::GetPathRoot($resolved)) {
    exit 1
}

try {
    Wait-Process -Id $WaitPid -ErrorAction SilentlyContinue
} catch {
}
Start-Sleep -Milliseconds 300

function Clear-ReadOnly([System.IO.FileSystemInfo] $Item) {
    try { $Item.IsReadOnly = $false } catch {}
    try {
        $Item.Attributes = $Item.Attributes -band (-bnot [System.IO.FileAttributes]::ReadOnly)
    } catch {}
}

function Remove-Cache([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $item = Get-Item -LiteralPath $Path -Force
    Clear-ReadOnly $item
    $reparse = [System.IO.FileAttributes]::ReparsePoint
    if (($item.Attributes -band $reparse) -ne 0) {
        Remove-Item -LiteralPath $Path -Force
        return
    }
    if ($item.PSIsContainer) {
        Get-ChildItem -LiteralPath $Path -Force | ForEach-Object {
            Remove-Cache $_.FullName
        }
    }
    if (Test-Path -LiteralPath $Path) {
        $again = Get-Item -LiteralPath $Path -Force
        Clear-ReadOnly $again
        Remove-Item -LiteralPath $Path -Force
    }
}

Remove-Cache $Home
