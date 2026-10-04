# Rebuild the OCR models the APK ships
#
# Three files come out, all under app/src/main/assets/ocr (gitignored, they are ~6 MiB of build
# product): PP-OCRv6 tiny's detector and recognizer, straight from HuggingFace with nothing
# changed but their shapes, plus the recognizer's character table. The app reads all three
# (channel/LwOcr.kt: det.onnx, rec.onnx, rec_dict.txt), so leaving the table out was leaving the
# recogniser without its alphabet.
#
# Why so little: the official ONNX exports have every spatial dimension dynamic
# (`DynamicDimension.0`) and QNN refuses dynamic shapes outright, so the only edit needed is to
# pin them (640x640 for det, 48x320 for rec). Everything else is left alone on purpose —
# see docs/step8-record.md for the two routes that were tried and what each cost.
#
# The mirror is not a preference. huggingface.co itself does not answer from this network (curl
# exits 28 after the whole timeout, on every one of the three files), while hf-mirror.com serves
# the same paths. Override -Base when that stops being true.
#
# usage: pwsh -File tools/ocr/build-models.ps1
#        pwsh -File tools/ocr/build-models.ps1 -Python C:\path\to\venv\Scripts\python.exe
param(
    # Interpreter that has `onnx`, `numpy` and `pyyaml` installed
    [string]$Python = 'python',
    # Repository root, so the defaults work on any machine rather than only on the one this file
    # was first written on
    [string]$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    # Where the downloads and the pinned models go
    [string]$Work = '',
    # Where the APK picks them up
    [string]$Out = '',
    [string]$Base = 'https://hf-mirror.com'
)

$ErrorActionPreference = 'Stop'
$Work = if ($Work) { $Work } else { Join-Path $Repo 'build\ocr-work' }
$Out = if ($Out) { $Out } else { Join-Path $Repo 'app\src\main\assets\ocr' }

& $Python -c "import onnx, numpy, yaml" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "$Python has no onnx/numpy/pyyaml: python -m pip install onnx numpy pyyaml"
}

$models = @(
    @{ name = 'det'; kind = 'det'; repo = 'PaddlePaddle/PP-OCRv6_tiny_det_onnx' },
    @{ name = 'rec'; kind = 'rec'; repo = 'PaddlePaddle/PP-OCRv6_tiny_rec_onnx' }
)

New-Item -ItemType Directory -Force -Path $Work, $Out | Out-Null
$rewrite = Join-Path $PSScriptRoot 'rewrite_onnx.py'

foreach ($model in $models) {
    $name = $model.name
    $raw = Join-Path $Work "$name.onnx"
    $pinned = Join-Path $Out "$name.onnx"
    Write-Host "=== $name"
    if (-not (Test-Path $raw)) {
        $url = "$Base/$($model.repo)/resolve/main/inference.onnx"
        Write-Host "  fetching $url"
        curl.exe -sL --max-time 300 --retry 2 $url -o $raw
        if ($LASTEXITCODE -ne 0) { throw "could not fetch $url (curl exit $LASTEXITCODE)" }
    }
    & $Python $rewrite --kind $model.kind --input $raw --output $pinned
    if ($LASTEXITCODE -ne 0) { throw "pinning failed for $name" }
}

# The recogniser's character table comes out of the official inference.yml rather than the onnx,
# and it is one file for both models: only rec has a dictionary
$yml = Join-Path $Work 'inference.yml'
if (-not (Test-Path $yml)) {
    $url = "$Base/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.yml"
    Write-Host "=== dictionary"
    Write-Host "  fetching $url"
    curl.exe -sL --max-time 300 --retry 2 $url -o $yml
    if ($LASTEXITCODE -ne 0) { throw "could not fetch $url (curl exit $LASTEXITCODE)" }
}
& $Python (Join-Path $PSScriptRoot 'extract_dict.py') --yml $yml --out (Join-Path $Out 'rec_dict.txt')
if ($LASTEXITCODE -ne 0) { throw "extracting the dictionary failed" }

Get-ChildItem $Out | ForEach-Object { "{0,10:N0}  {1}" -f $_.Length, $_.Name }
Write-Host "models written to $Out"
