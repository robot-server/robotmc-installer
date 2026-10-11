param(
    [Parameter(Mandatory = $true)]
    [long] $WaitPid,
    [Parameter(Mandatory = $true)]
    [string] $CacheDirectory,
    [Parameter(Mandatory = $true)]
    [string] $CancelFile
)

$name = Split-Path -Leaf $CacheDirectory
if ($name -ne '.robotmc-installer') {
    exit 1
}
$resolved = [System.IO.Path]::GetFullPath($CacheDirectory)
$user = [System.IO.Path]::GetFullPath($env:USERPROFILE)
if ($resolved -eq $user -or $resolved -eq [System.IO.Path]::GetPathRoot($resolved)) {
    exit 1
}

try {
    Wait-Process -Id $WaitPid -ErrorAction SilentlyContinue
} catch {
}
Start-Sleep -Milliseconds 300
if (Test-Path -LiteralPath $CancelFile) {
    exit 0
}

function Clear-ReadOnly([System.IO.FileSystemInfo] $Item) {
    try { $Item.IsReadOnly = $false } catch {}
    try {
        $Item.Attributes = $Item.Attributes -band (-bnot [System.IO.FileAttributes]::ReadOnly)
    } catch {}
}

function Remove-ReparsePoint([string] $Path) {
    # Windows PowerShell 5.1: Remove-Item -Force follows a directory symlink or junction
    # and deletes the outside target. Delete() removes only the reparse point.
    # Do not clear read-only here. SetFileAttributes follows the link and changes the target.
    $reparse = [System.IO.FileAttributes]::ReparsePoint
    $attrs = [System.IO.File]::GetAttributes($Path)
    if (($attrs -band $reparse) -eq 0) {
        throw "refusing to follow a directory link: $Path"
    }
    $item = Get-Item -LiteralPath $Path -Force
    $linkFull = [System.IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    $itemFull = [System.IO.Path]::GetFullPath($item.FullName).TrimEnd('\', '/')
    if (-not $linkFull.Equals($itemFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "refusing to follow a directory link: $Path"
    }
    if (($item.Attributes -band $reparse) -eq 0) {
        throw "refusing to follow a directory link: $Path"
    }
    $item.Delete()
}

function Remove-Cache([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $item = Get-Item -LiteralPath $Path -Force
    $reparse = [System.IO.FileAttributes]::ReparsePoint
    if (($item.Attributes -band $reparse) -ne 0) {
        Remove-ReparsePoint $Path
        return
    }
    Clear-ReadOnly $item
    if ($item.PSIsContainer) {
        $children = @([System.IO.Directory]::EnumerateFileSystemEntries($Path))
        foreach ($child in $children) {
            Remove-Cache $child
        }
    }
    if (Test-Path -LiteralPath $Path) {
        $again = Get-Item -LiteralPath $Path -Force
        if (($again.Attributes -band $reparse) -ne 0) {
            Remove-ReparsePoint $Path
            return
        }
        Clear-ReadOnly $again
        Remove-Item -LiteralPath $Path -Force
    }
}

Remove-Cache $CacheDirectory
