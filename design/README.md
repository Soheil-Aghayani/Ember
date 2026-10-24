# Ember design

`tokens.json` is the single source of truth for both apps. `android/` and `desktop/` read from it; do not hard-code colours, sizes or fonts in either app.

## Direction
Claude's warmth (cream paper, ink text, generous whitespace) with Firefox's orange and violet as accents, laid out to Apple's Human Interface Guidelines: clear hierarchy, large titles, grouped lists, 44pt+ targets, subtle motion.

## Rules
- **Colour:** accent orange is for the primary action and the connected state only. Use `accentText` for orange text; plain `accent` fails AA as text on cream. All listed pairs pass WCAG AA.
- **Dark mode:** first-class, not an inversion. Warm near-black, never pure `#000`.
- **Type:** Inter (SIL OFL, bundleable). Follow the HIG scale in `tokens.json`. Respect OS text-size settings.
- **Icons:** do not use SF Symbols or SF Pro in the Android or Windows apps (Apple licence limits them to Apple platforms). Use original icons in the style noted in `tokens.json`; they will be generated with GPT and reviewed for licence-safety.
- **Brand:** no Firefox logo, no look-alike fox mark. Ember's mark is an original ember/flame.
- **Reference only:** the Sketch symbol libraries and HIG are references, not assets we copy.

## Third-party assets
- Country flags in `desktop/src/main/resources/flags/` are from the circle-flags set (MIT licence, github.com/HatScripts/circle-flags).
- Icons come from the Solar icon set by 480 Design (CC BY 4.0, https://creativecommons.org/licenses/by/4.0/). Ember uses converted copies in `android/src/main/res/drawable/ic_solar_*` and `desktop/src/main/resources/icons/`.
- Inter font: SIL Open Font License 1.1 (see `design/Inter-LICENSE.txt`).
