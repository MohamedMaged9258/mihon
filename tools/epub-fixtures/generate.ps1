param(
    [string]$OutputDirectory = "$PSScriptRoot/../../app/src/debug/assets/epub-fixtures"
)

$ErrorActionPreference = 'Stop'
$epoch = [DateTimeOffset]::Parse('2000-01-01T00:00:00Z')
$utf8 = [System.Text.UTF8Encoding]::new($false)
$png = [Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAD0lEQVR4nGP4z8DAwMAAAAYAAf4CB0cAAAAASUVORK5CYII=')

function Write-Epub {
    param([string]$Path, [hashtable]$Entries)
    $parent = Split-Path -Parent $Path
    [System.IO.Directory]::CreateDirectory($parent) | Out-Null
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Create)
    try {
        $archive = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            $ordered = @('mimetype') + @($Entries.Keys | Where-Object { $_ -ne 'mimetype' } | Sort-Object)
            foreach ($name in $ordered) {
                $compression = if ($name -eq 'mimetype') {
                    [System.IO.Compression.CompressionLevel]::NoCompression
                } else {
                    [System.IO.Compression.CompressionLevel]::Optimal
                }
                $entry = $archive.CreateEntry($name, $compression)
                $entry.LastWriteTime = $epoch
                $entryStream = $entry.Open()
                try {
                    $value = $Entries[$name]
                    $bytes = if ($value -is [byte[]]) { $value } else { $utf8.GetBytes([string]$value) }
                    $entryStream.Write($bytes, 0, $bytes.Length)
                } finally {
                    $entryStream.Dispose()
                }
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

$container = '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'
$css = 'body{font-family:serif;line-height:1.5;margin:8%;} img{max-width:75%;} .marker{font-weight:bold;color:#7b1fa2;}'
$imageItem = '<item id="image" href="image.png" media-type="image/png"/>'

$epub2 = @{
    'mimetype' = 'application/epub+zip'
    'META-INF/container.xml' = $container
    'OEBPS/package.opf' = "<?xml version=`"1.0`" encoding=`"utf-8`"?><package xmlns=`"http://www.idpf.org/2007/opf`" unique-identifier=`"id`" version=`"2.0`"><metadata xmlns:dc=`"http://purl.org/dc/elements/1.1/`"><dc:identifier id=`"id`">urn:mihon:fixture:epub2</dc:identifier><dc:title>Mihon EPUB 2 Fixture</dc:title><dc:language>en</dc:language><dc:rights>CC0-1.0</dc:rights></metadata><manifest><item id=`"ncx`" href=`"toc.ncx`" media-type=`"application/x-dtbncx+xml`"/><item id=`"css`" href=`"style.css`" media-type=`"text/css`"/>$imageItem<item id=`"s1`" href=`"section1.xhtml`" media-type=`"application/xhtml+xml`"/><item id=`"s2`" href=`"section2.xhtml`" media-type=`"application/xhtml+xml`"/><item id=`"s3`" href=`"section3.xhtml`" media-type=`"application/xhtml+xml`"/></manifest><spine toc=`"ncx`"><itemref idref=`"s1`"/><itemref idref=`"s2`"/><itemref idref=`"s3`"/></spine></package>"
    'OEBPS/toc.ncx' = '<?xml version="1.0" encoding="utf-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="urn:mihon:fixture:epub2"/></head><docTitle><text>Mihon EPUB 2 Fixture</text></docTitle><navMap><navPoint id="n1" playOrder="1"><navLabel><text>Opening</text></navLabel><content src="section1.xhtml#epub2-start"/></navPoint><navPoint id="n2" playOrder="2"><navLabel><text>Illustration</text></navLabel><content src="section2.xhtml#epub2-image"/></navPoint><navPoint id="n3" playOrder="3"><navLabel><text>Restoration</text></navLabel><content src="section3.xhtml#epub2-restore"/></navPoint></navMap></ncx>'
    'OEBPS/style.css' = $css
    'OEBPS/image.png' = $png
    'OEBPS/section1.xhtml' = '<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Opening</title><link rel="stylesheet" href="style.css"/></head><body><h1 id="epub2-start">Opening</h1><p class="marker">EPUB2-PASSAGE-START</p><p>A deterministic public-domain diagnostic passage.</p></body></html>'
    'OEBPS/section2.xhtml' = '<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Illustration</title><link rel="stylesheet" href="style.css"/></head><body><h1 id="epub2-image">Illustration</h1><img src="image.png" alt="Two pixel fixture illustration"/><p class="marker">EPUB2-PASSAGE-IMAGE</p></body></html>'
    'OEBPS/section3.xhtml' = '<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Restoration</title><link rel="stylesheet" href="style.css"/></head><body><h1 id="epub2-restore">Restoration</h1><p class="marker">EPUB2-PASSAGE-RESTORE</p><p>This paragraph is the process recovery checkpoint.</p></body></html>'
}

$epub3 = @{
    'mimetype' = 'application/epub+zip'
    'META-INF/container.xml' = $container
    'OEBPS/package.opf' = "<?xml version=`"1.0`" encoding=`"utf-8`"?><package xmlns=`"http://www.idpf.org/2007/opf`" unique-identifier=`"id`" version=`"3.0`"><metadata xmlns:dc=`"http://purl.org/dc/elements/1.1/`"><dc:identifier id=`"id`">urn:mihon:fixture:epub3</dc:identifier><dc:title>Mihon EPUB 3 Arabic Fixture</dc:title><dc:language>en</dc:language><dc:language>ar</dc:language><dc:rights>CC0-1.0</dc:rights><meta property=`"dcterms:modified`">2000-01-01T00:00:00Z</meta></metadata><manifest><item id=`"nav`" href=`"nav.xhtml`" media-type=`"application/xhtml+xml`" properties=`"nav`"/><item id=`"css`" href=`"style.css`" media-type=`"text/css`"/>$imageItem<item id=`"intro`" href=`"text/intro.xhtml`" media-type=`"application/xhtml+xml`"/><item id=`"nested`" href=`"text/nested.xhtml`" media-type=`"application/xhtml+xml`"/><item id=`"arabic`" href=`"text/arabic.xhtml`" media-type=`"application/xhtml+xml`"/></manifest><spine><itemref idref=`"intro`"/><itemref idref=`"nested`"/><itemref idref=`"arabic`"/></spine></package>"
    'OEBPS/nav.xhtml' = '<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><h1>Contents</h1><ol><li><a href="text/intro.xhtml#epub3-start">Introduction</a><ol><li><a href="text/nested.xhtml?mode=test#encoded%20marker">Nested encoded target</a></li></ol></li><li><a href="text/arabic.xhtml#arabic-restore">العربية</a></li></ol></nav></body></html>'
    'OEBPS/style.css' = $css
    'OEBPS/image.png' = $png
    'OEBPS/text/intro.xhtml' = '<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Introduction</title><link rel="stylesheet" href="../style.css"/></head><body><h1 id="epub3-start">EPUB 3</h1><p class="marker">EPUB3-PASSAGE-START</p><img src="../image.png" alt="Fixture illustration"/></body></html>'
    'OEBPS/text/nested.xhtml' = '<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Nested</title><link rel="stylesheet" href="../style.css"/></head><body><h2 id="encoded marker">Nested section</h2><p class="marker">EPUB3-PASSAGE-NESTED</p></body></html>'
    'OEBPS/text/arabic.xhtml' = '<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ar" dir="rtl"><head><title>العربية</title><link rel="stylesheet" href="../style.css"/></head><body><h1 id="arabic-restore">اختبار القراءة</h1><p class="marker">EPUB3-PASSAGE-ARABIC</p><p>هذا نص عربي للاختبار مع English words 2026 داخل السطر.</p></body></html>'
}

$imageEpub = @{
    'mimetype' = 'application/epub+zip'
    'META-INF/container.xml' = $container
    'OEBPS/package.opf' = "<?xml version=`"1.0`"?><package xmlns=`"http://www.idpf.org/2007/opf`" unique-identifier=`"id`" version=`"2.0`"><metadata xmlns:dc=`"http://purl.org/dc/elements/1.1/`"><dc:identifier id=`"id`">urn:mihon:fixture:image</dc:identifier><dc:title>Mihon Image EPUB Fixture</dc:title><dc:language>en</dc:language><dc:rights>CC0-1.0</dc:rights></metadata><manifest>$imageItem<item id=`"p1`" href=`"page1.xhtml`" media-type=`"application/xhtml+xml`"/><item id=`"p2`" href=`"page2.xhtml`" media-type=`"application/xhtml+xml`"/></manifest><spine><itemref idref=`"p1`"/><itemref idref=`"p2`"/></spine></package>"
    'OEBPS/image.png' = $png
    'OEBPS/page1.xhtml' = '<html xmlns="http://www.w3.org/1999/xhtml"><body><img src="image.png" alt="page one"/></body></html>'
    'OEBPS/page2.xhtml' = '<html xmlns="http://www.w3.org/1999/xhtml"><body><img src="image.png" alt="page two"/></body></html>'
}

$external = $epub3.Clone()
$external['OEBPS/text/intro.xhtml'] = '<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>External blocked</title><link rel="stylesheet" href="http://127.0.0.1:38080/trap.css"/></head><body><h1 id="external-start">External policy fixture</h1><img src="http://127.0.0.1:38080/trap.png" alt="must be blocked"/><p>REMOTE-REQUEST-MUST-NOT-REACH-SOCKET</p></body></html>'
$external['OEBPS/package.opf'] = $external['OEBPS/package.opf'].Replace('urn:mihon:fixture:epub3', 'urn:mihon:fixture:external').Replace('Mihon EPUB 3 Arabic Fixture', 'Mihon External Resource Fixture')

[System.IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$definitions = @(
    @{ id='epub2'; title='EPUB 2 / NCX'; file='epub2.epub'; entries=$epub2 },
    @{ id='epub3'; title='EPUB 3 / nested ToC / Arabic'; file='epub3.epub'; entries=$epub3 },
    @{ id='image-epub'; title='Image-oriented EPUB'; file='image-epub.epub'; entries=$imageEpub },
    @{ id='external'; title='External resource (fail closed)'; file='external.epub'; entries=$external }
)

$manifest = @()
foreach ($definition in $definitions) {
    $path = Join-Path $OutputDirectory $definition.file
    Write-Epub -Path $path -Entries $definition.entries
    $manifest += [ordered]@{
        id = $definition.id
        title = $definition.title
        assetPath = "epub-fixtures/$($definition.file)"
        sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        rights = 'CC0-1.0; authored for Mihon diagnostics'
    }
}

$validBytes = [System.IO.File]::ReadAllBytes((Join-Path $OutputDirectory 'epub3.epub'))
$corruptPath = Join-Path $OutputDirectory 'corrupt.epub'
[System.IO.File]::WriteAllBytes($corruptPath, $validBytes[0..95])
$manifest += [ordered]@{
    id = 'corrupt'
    title = 'Truncated / corrupt EPUB'
    assetPath = 'epub-fixtures/corrupt.epub'
    sha256 = (Get-FileHash -LiteralPath $corruptPath -Algorithm SHA256).Hash.ToLowerInvariant()
    rights = 'CC0-1.0; derived from the authored EPUB 3 diagnostic fixture'
}

$json = [ordered]@{ generatedAt = '2000-01-01T00:00:00Z'; fixtures = $manifest } | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText((Join-Path $OutputDirectory 'fixtures.json'), $json, $utf8)
