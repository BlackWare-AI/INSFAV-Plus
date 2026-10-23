# 1.1.4 / build 6: equal basic settings cards

The six basic settings cards share equal grid row heights. Their existing
Matched Images / Missing Images row supplies the normal reference height;
intrinsic sizing can still grow every row together for larger or wrapped text.
The rule is scoped to #basic-metrics. Columns, gaps, typography, values, copy
button/glyph and the separate Advanced/Fetcher metrics remain unchanged.

A temporary Chromium geometry probe checks all six widths/heights against the
original tallest card at 280/320/393/480/600px widths, 100%/200% root font sizes,
and Turkish/English (20 combinations). All match, with no grid overflow and
all child elements and the enlarged copy icon contained. The existing clipboard
integration passes for actual copied usernames, rejection and empty state.
The 17 core JS checks, 127 browser checks and settings-language check pass.
No permanent test is added for this small CSS change. Packaged assets are
regenerated from web/. Current-source CI gates precede signed publication.

VersionCode increases from 5 to 6 with the existing Plus signing certificate.
Physical-device behavior remains unverified.
