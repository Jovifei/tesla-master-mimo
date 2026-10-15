Add-Type -AssemblyName System.Drawing
$dest = Join-Path $PSScriptRoot '../../miniprogram/src/assets/tabs'
$dest = [IO.Path]::GetFullPath($dest)
New-Item -ItemType Directory -Path $dest -Force | Out-Null
foreach ($variant in @(@{suffix='';color='#737373'},@{suffix='-selected';color='#171717'})) {
  foreach ($kind in @('vehicle','route','energy','analysis')) {
    $bitmap = [Drawing.Bitmap]::new(81,81)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.ScaleTransform(3.375,3.375)
    $color = [Drawing.ColorTranslator]::FromHtml($variant.color)
    $pen = [Drawing.Pen]::new($color,1.6)
    $pen.StartCap = [Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [Drawing.Drawing2D.LineCap]::Round
    $brush = [Drawing.SolidBrush]::new($color)
    switch ($kind) {
      'vehicle' {
        $graphics.DrawPolygon($pen,[Drawing.PointF[]]@([Drawing.PointF]::new(5,10),[Drawing.PointF]::new(7,5),[Drawing.PointF]::new(17,5),[Drawing.PointF]::new(19,10)))
        $graphics.DrawRectangle($pen,4,10,16,8)
        $graphics.FillEllipse($brush,6,12,3,3); $graphics.FillEllipse($brush,15,12,3,3)
        $graphics.DrawLine($pen,6,18,6,20); $graphics.DrawLine($pen,18,18,18,20)
      }
      'route' {
        $graphics.DrawEllipse($pen,3,3,5,5); $graphics.DrawEllipse($pen,16,16,5,5)
        $graphics.DrawBezier($pen,6,9,23,3,3,21,16,18)
      }
      'energy' {
        $graphics.FillPolygon($brush,[Drawing.PointF[]]@([Drawing.PointF]::new(14,2),[Drawing.PointF]::new(5,14),[Drawing.PointF]::new(11,14),[Drawing.PointF]::new(9,22),[Drawing.PointF]::new(20,9),[Drawing.PointF]::new(13,9)))
      }
      'analysis' {
        $graphics.DrawRectangle($pen,4,4,16,16)
        $graphics.FillRectangle($brush,7,13,2,4); $graphics.FillRectangle($brush,11,7,2,10); $graphics.FillRectangle($brush,15,10,2,7)
      }
    }
    $bitmap.Save((Join-Path $dest ($kind+$variant.suffix+'.png')),[Drawing.Imaging.ImageFormat]::Png)
    $pen.Dispose(); $brush.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
  }
}
