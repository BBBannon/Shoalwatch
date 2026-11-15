# Regenerates the test clip and the pre-cut HLS files. Run from this folder: powershell -File make_media.ps1
# Needs ffmpeg on PATH (written against 8.1.2). Uses only ffmpeg's built-in testsrc2 and sine sources: no third-party content.
$ff = (Get-Command ffmpeg).Source
& $ff -hide_banner -y -f lavfi -i "testsrc2=size=320x180:rate=24" -f lavfi -i "sine=frequency=440:sample_rate=48000" `
      -t 10 -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p -preset veryslow -crf 38 -g 24 -c:a aac -b:a 64k -movflags +faststart clip.mp4
New-Item -ItemType Directory -Force hls | Out-Null
& $ff -hide_banner -y -i clip.mp4 -c copy -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_filename "hls/seg%d.ts" hls/main.m3u8
Get-FileHash clip.mp4, hls/* -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLower())  $([IO.Path]::GetFileName($_.Path))" } | Set-Content CHECKSUMS.txt
