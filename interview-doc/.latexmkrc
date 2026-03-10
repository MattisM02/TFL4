# latexmk-Konfiguration für das Interview-Dokument
$lualatex = 'lualatex -synctex=1 -interaction=nonstopmode -file-line-error %O %S';
$pdf_mode = 4;        # 4 = lualatex
$out_dir  = 'out';
$clean_ext = 'aux log out toc fdb_latexmk fls synctex.gz bcf run.xml';
