param(
    [Parameter(Mandatory = $true)] [string] $Ndk,
    [string] $Dependencies = (Join-Path $PSScriptRoot '../../../.build-tools')
)

$ErrorActionPreference = 'Stop'
$Ndk = (Resolve-Path -LiteralPath $Ndk).Path
$Dependencies = [System.IO.Path]::GetFullPath($Dependencies)
New-Item -ItemType Directory -Path $Dependencies -Force | Out-Null
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$toolchain = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'

function Get-PinnedSource($Directory, $Repository, $Commit) {
    if (!(Test-Path -LiteralPath (Join-Path $Directory '.git'))) {
        & git init --quiet $Directory
        if ($LASTEXITCODE -ne 0) { throw "Could not initialize $Directory" }
    }
    $head = & git -C $Directory rev-parse --verify --quiet HEAD
    if ($head -eq $Commit) { return }
    if ($head) { throw "Unexpected revision in $Directory. Use a clean dependencies directory." }

    & git -C $Directory fetch --quiet --depth 1 "https://github.com/$Repository.git" $Commit
    if ($LASTEXITCODE -ne 0) { throw "Could not fetch $Repository at $Commit" }
    & git -C $Directory checkout --quiet --detach FETCH_HEAD
    if ($LASTEXITCODE -ne 0) { throw "Could not check out $Repository at $Commit" }
}

$byeDpi = Join-Path $Dependencies 'byedpi-v0.17.3'
$hevTunnel = Join-Path $Dependencies 'hev-socks5-tunnel-2.20.0'
Get-PinnedSource $byeDpi 'hufrea/byedpi' '7efde1b1296eaaa187b70e951894dde17527489c'
Get-PinnedSource $hevTunnel 'heiher/hev-socks5-tunnel' '75dbc1e9ace6c70d3d1b94df7c283efe1de7effc'
Get-PinnedSource (Join-Path $hevTunnel 'src/core') 'heiher/hev-socks5-core' 'b43fa857fb39c854da01d13bc55eded66b4f4a3b'
Get-PinnedSource (Join-Path $hevTunnel 'third-part/hev-task-system') 'heiher/hev-task-system' 'ded55c412b3951bc7decb5cb3b9135701e762998'
Get-PinnedSource (Join-Path $hevTunnel 'third-part/lwip') 'heiher/lwip' '8115cd6150ab32500db3d4d8bd8ad7ec6648534c'
Get-PinnedSource (Join-Path $hevTunnel 'third-part/yaml') 'heiher/yaml' '0c82efd10dc67b9e7f159d2c7f5d1dc3c1b5bc11'

$patchedByeDpi = Join-Path $Dependencies 'youtube-byedpi-sources'
New-Item -ItemType Directory -Path $patchedByeDpi -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $byeDpi 'main.c'), (Join-Path $byeDpi 'extend.c') -Destination $patchedByeDpi
& git -C $patchedByeDpi apply --ignore-space-change (Join-Path $PSScriptRoot 'byedpi-host-suffix.patch')
if ($LASTEXITCODE -ne 0) { throw 'Could not patch the ByeDPI hostname matcher.' }

$hevOutput = Join-Path $Dependencies 'youtube-hev-libs'
$hevObjects = Join-Path $Dependencies 'youtube-hev-obj'
& (Join-Path $Ndk 'ndk-build.cmd') 'NDK_PROJECT_PATH=null' "APP_BUILD_SCRIPT=$hevTunnel/Android.mk" `
    'APP_ABI=arm64-v8a armeabi-v7a' 'APP_PLATFORM=android-21' 'APP_MODULES=hev-socks5-tunnel' `
    'REV_ID=75dbc1e' 'APP_CFLAGS=-DPKGNAME=com/amurcanov/tgwsproxy -DCLSNAME=YoutubeTunnel' `
    "NDK_OUT=$hevObjects" "NDK_LIBS_OUT=$hevOutput" -j4
if ($LASTEXITCODE -ne 0) { throw 'The Android packet relay failed to build.' }

$sources = @('conev.c', 'proxy.c', 'desync.c', 'packets.c', 'mpool.c') |
    ForEach-Object { Join-Path $byeDpi $_ }
$sources += Join-Path $patchedByeDpi 'extend.c'
$targets = [ordered]@{ 'arm64-v8a' = 'aarch64-linux-android24'; 'armeabi-v7a' = 'armv7a-linux-androideabi21' }
foreach ($abi in $targets.Keys) {
    $output = Join-Path $project "app/src/main/jniLibs/$abi"
    $library = Join-Path $output 'libyoutube-dpi.so'
    & (Join-Path $toolchain 'clang.exe') "--target=$($targets[$abi])" -D_GNU_SOURCE -Wno-macro-redefined `
        -Os -fPIC -ffunction-sections -fdata-sections -fvisibility=hidden -shared "-I$patchedByeDpi" "-I$byeDpi" `
        '-Wl,--gc-sections,--no-undefined,-z,max-page-size=16384,-soname,libyoutube-dpi.so' `
        (Join-Path $PSScriptRoot 'youtube-dpi.c') @sources -o $library
    if ($LASTEXITCODE -ne 0) { throw "ByeDPI failed to build for $abi." }
    & (Join-Path $toolchain 'llvm-strip.exe') --strip-unneeded $library
    if ($LASTEXITCODE -ne 0) { throw "Could not strip $library." }
    Copy-Item -LiteralPath (Join-Path $hevOutput "$abi/libhev-socks5-tunnel.so") -Destination $output
}
