# AndroidSVG 1.4

`MediaDecoder.kt` uses `com.caverock:androidsvg-aar:1.4` to render static SVG resources into bounded bitmaps. Copyright Paul LeBeau, Cave Rock Software Ltd.; Apache License 2.0 (LICENSE.txt).

Official project: https://github.com/BigBadaboom/androidsvg
Official release/dependency documentation: https://bigbadaboom.github.io/androidsvg/
Maven Central artifact: https://repo.maven.apache.org/maven2/com/caverock/androidsvg-aar/1.4/

Internal entity expansion is disabled. No external file resolver is registered, so linked remote images, fonts and styles are not fetched. Scripts and animation are not executed. The renderer's documented SVG filter limitation remains. The media loader supplies an already-resolved app-readable URI; the SVG decoder does not read environment paths or make network requests.
