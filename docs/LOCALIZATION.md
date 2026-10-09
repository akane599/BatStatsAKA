# Localization

The default interface is English. Spanish (`values-es`, shared across Spanish locales) and Turkish (`values-tr`) provide complete translations of the current string resources. Device language selects the resources. Other languages use English fallback.

At baseline `76bc831`, all17 language directories contained162 strings identical to the English default. Seven density/night files repeated six English strings as well. They were placeholders, not translations. Consolidating these copies into the default resources preserves their actual displayed language and prevents stale reset descriptions from overriding corrected defaults. No existing translated text was removed. The earlier progress record inferred multilingual support from directory names; that assumption was incorrect.

Spanish and Turkish translations preserve units, format arguments, missing-data distinctions, observation periods and warnings about estimated consumption. They have been checked for completeness and placeholders; native-speaker review remains welcome. Compilation and actual screen-review results belong in [VALIDATION.md](VALIDATION.md), not in claims of language support inferred from file counts.

For another language, translate the current `values/strings.xml` into a language-qualified directory, preserve `%1$s`, `%2$d`, `%%` and `\n`, and add the locale to `androidResources.localeFilters`. Do not copy English sentences as placeholders. Run `python3 scripts/check_resources.py`, Android Lint and resource compilation. Update the script's locale list, then inspect important screens at large font sizes. Technical diagnostic codes, report keys, SI units, package names and identifiers intentionally remain stable.
