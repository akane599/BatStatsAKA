# android-kit · 2026.10.03.3

Ubuntu üzerinde yeni, fork veya mevcut Android projeleri için kişisel Claude Code kurulumu. Sidequest + Model Gateway + live-rules + codebase-mapper + quartermaster temeli korunur. Plugin etkinleştirmeleri, hook’lar ve model ayarları **local scope** ile `.claude/settings.local.json` içine yazılır.

## Hızlı kullanım

Proje kökünden:

```bash
bash /kitin/yolu/android-kit/setup.sh --dry-run .
bash /kitin/yolu/android-kit/setup.sh .
claude
# Yeni Claude oturumunda: /bootstrap
```

İlk tam kurulum `android-kit` komutunu `~/.local/bin` altına ekler. Sonraki projelerde, proje kökünde `android-kit` yeterlidir. Kit klasörü proje içindeyse git exclude’a eklenir. `~/.local/bin` PATH üzerinde olmalıdır.

Fork’a katkı göndereceksen:

```bash
android-kit --local-only
```

Bu seçenek kit dosyalarını Git’in yerel exclude dosyasına ekler; `.gitignore` dosyalarını değiştirmez. Seçim sonraki çalıştırmalarda korunur. **Zaten tracked olan dosyaları gizlemez/untrack etmez.** Mevcut CLAUDE.md veya paylaşılan ayarlar dönüştürülmüşse bu diff’leri upstream commit’inden ayrı tut.

Sadece proje dosyalarını kurmak, hiçbir plugin/makine kurulumu yapmamak için:

```bash
bash /kitin/yolu/android-kit/setup.sh --no-plugins --local-only .
```

Bu mod Claude CLI, Node veya ağ istemez; Git + Python 3.9+ + Bash + flock gerekir. `android-kit` komutunu makineye kurmaz. Tam kurulumda ayrıca Claude Code, Node 18+ gerekir; Android doğrulaması için projenin gerektirdiği JDK/SDK gerekir.

## Güncellemelerde ne korunur?

- Proje dosyaları değiştirilmeden önce `.claude/kit/backups/<zaman>/files/` altında snapshot alınır; `snapshot.json` önceki dosya envanterini tutar.
- Mevcut `CLAUDE.md`, ilk benimsemede `CLAUDE.upstream.md` olur. Bu isim doluysa iki dosya da kaybolmaz; o andaki CLAUDE.md snapshot’ta korunur.
- Kit dosyalarının SHA-256 değerleri takip edilir. Senin değiştirdiğin dosya korunur; yeni şablon `.claude/kit/incoming/<yol>` altında bırakılır. `.claude/kit/conflicts.json` birleştirme listesidir.
- Eski kitte hash olmadığı için farklı dosyalar ilk yükseltmede ihtiyatlı biçimde korunur. `/bootstrap` bu farkları mevcut proje kurallarını koruyarak uzlaştırmalıdır.
- JSON bozuksa, yönetilen yolda symlink varsa veya manifest proje dışına taşan yol içeriyorsa kurulum önceden durur.
- Ayar birleştirme, aynı hook grubundaki kullanıcı handler’larını korur. Yalnızca tanınan eski hook tanımları değiştirilir.
- Kurulum repo kökünü ister; Git worktree içindeki `.git` dosyasını destekler. Eşzamanlı kurulumlar kilitlenir.

Snapshot otomatik rollback değildir. Geri almak için ilgili snapshot’ın `files/` ağacını mevcut dosyalarla karşılaştır, yalnızca geri almak istediğin dosyaları kopyala. `absent` listesindeki sonradan yaratılmış dosyaları inceleyerek kaldır. Snapshot, plugin cache’i, makine routing profilini veya Git index’ini geri sarmaz. Yedekler hassas yerel ayarlar içerebilir; otomatik commit edilmez ve otomatik silinmez.

## Sidequest model / efor dağılımı

| İş | Kategori | Model / efor |
|---|---|---|
| Ana orkestratör | ana oturum | Opus / high |
| Mekanik kod, sınırlı açıklama | `coding.easy`, `implementation-explanation` | GPT-6 Luna / medium |
| Var olan testi yalnızca çalıştırma | `test-execution` | GPT-6 Luna / medium, read-only |
| Normal kod, debugging, keşif, spike | `coding.normal`, `debugging`, `codebase-exploration`, `spike-investigation` | GPT-6.1 Sol / high |
| Yeni regresyon testi / test onarımı | `behavior-verification` | GPT-6.1 Sol / high |
| Zor kod, deney, plan denetimi | `coding.hard.frontier`, `experiment.frontier`, `plan-review.frontier` | GPT-6 Astra / high |
| UI tasarımı ve uygulaması | `interaction-design-implementation` | Opus / high |
| Mekanik UI değişikliği | `ui.tweak` | Sonnet / medium |
| Kod denetimi | `review-audit` | Opus / high |
| Ekran görüntüsü değerlendirmesi | `visual-evaluation` | Opus / medium |
| Belge / kaynak araştırması | `source-lookup`, `evidence-research` | Sonnet / medium |
| İki farklı başarısız yaklaşım sonrası | `escalation` | Opus / xhigh |
| Opus denemesi de sonuçsuzsa | `frontier` | Fable / xhigh |

Ana oturumun `model: opus` / `effortLevel: high` varsayılanları artık ayarlara da yazılır; var olan yerel seçimlerin korunur. Claude alias’larının somut sürümü sağlayıcıya göre değişebilir. Mevcut oturum/CLI/environment seçimleri ayarlardan daha yüksek öncelikli olabilir.

Profil `~/.claude/sidequest` altında makine çapında, board seçimi proje bazındadır. CLI/dashboard üzerinden değiştirdiğin profil korunur. Kitin tanımladığı kategori eşlemelerine dönmek için `android-kit --reset-routing` kullan; profildeki ekstra kategorileri silmez. Başka bir kişisel profil kullanan board değiştirilmez; board-local category override’ları da kalır.

Sidequest **5.6.0** kaynaklarıyla doğrulandı: Astra rotaları, kimliğinde `frontier` bulunmayan kategorilerde Sol’a taşınabiliyor. Bu nedenle kitin `.frontier` kategorileri korunur ve stock `coding.hard`/`experiment` bu profilde kapalıdır. Bu davranışı gelecekteki tüm sürümler için garanti sayma.

`sidequest models --full` gerçek çözülmüş rota ve fallback’i gösterir. Katalogda olmayan GPT rotalarının Claude fallback’i vardır; **dispatch başladıktan sonraki auth/ağ/model hatası otomatik fallback garantisi değildir.** Fable erişimi de hesaba bağlıdır. SDK, auth veya ağ hatasını pahalı modele yükseltmek yerine ortam sorununu çöz.

## Plugin paketi

Temel: sidequest, model-gateway, live-rules, codebase-mapper, quartermaster.

Android: kotlin-lsp-android, android-kmp-playbook, droidforge, android-emulator-qa, claude-security, security-guidance, context7, session-report. Java kaynağı bulunursa jdtls-lsp eklenir. Kotlin LSP Android desteği upstream’de deneysel olduğundan Gradle çıktısı asıl doğrulama kaynağıdır.

İsteğe bağlı:

```bash
android-kit --with observability
# veya seçerek: --with hookify,security-sweep
```

`observability`, model/efor seçimlerini kendi sonuçlarınla değerlendirmek için yararlı olabilir. Varsayılan açık değildir. Yeni bir orkestrasyon/auto-memory plugin’i eklenmedi; mevcut araçlarla aynı işi yapan ikinci döngü, kural ve hook çakışmalarını artırır. Web odaklı `design-with-claude` Android için varsayılan değildir; bu extra’nın kaynağı `main` olduğu için hareketlidir. Sabitlik gerekiyorsa `plugins.conf` içinde tam commit SHA kullan.

Topluluk plugin’leri `~/.claude/local-marketplace` altında klonlanır. Commit bulunamazsa artık default branch’e sessizce geçilmez. Yerel manifestler normalize edilir ve plugin **dizini** Claude validator’a verilir. Opt-in clone’lar ilk kez yalnızca seçildiğinde indirilir; başka projelerin kullandığı mevcut clone’lar korunur. Resmî/toolshed marketplace’leri hareketli kaynaklardır; tüm kit tamamen kilitlenmiş bir bağımlılık dağıtımı değildir.

Plugin kurulum hataları `state.json` içindeki `failed_plugins` alanına yazılır; setup kısmi kurulumda `3` koduyla çıkar. Başarılı plugin kaydı, canlı model isteği veya Android build testi yapıldığı anlamına gelmez.

## Komutlar

- `/bootstrap`: yeni projeyi oluşturur veya mevcut projeyi öğrenip baseline çıkarır; kişisel kuralları ve gelen şablon farklarını uzlaştırır.
- `/kit-doctor`: dosya çakışmaları, yerel scope, gerçek model rotaları ve ortam için salt okunur kontrol.
- `/plan-audit`: Claude planını Astra ile denetler; yüksek riskli işlerde ek review.
- `/bug-hunt [alan]`: mevcut koddaki sorunları arar; kabul edilen bulgular için reproducer + düzeltme.
- `/ui-overhaul`: brief → token/theme → ekranlar → screenshot + kod review.
- `/wrap-up`: kalıcı kuralları, kararları ve açık işleri düzenler.

UI tercihin korunur: UI Claude rotalarındadır. `ui-guard` plugin namespace’lerini ve göreli yolları tanır. Ancak keyfî `Bash`/Python dosya yazmalarını tam analiz etmez; bir güvenlik sandbox’ı değildir. Entegrasyonda değişen yolları da incele. `touch .claude/kit/ui-guard-off` proje bazında kapatır.

Compaction checkpoint’leri session bazındadır; iki oturum birbirinin bağlamını ezmez. Transcript akış halinde okunur, sınırlı son mesaj alıntıları saklanır. Bunlar geçmiş bağlamdır; yeni kullanıcı talimatlarının önüne geçmez.

## Build ve scaffold

Gerçek Gradle exit code’unu koruyarak kısa çıktı:

```bash
bash .claude/kit/gradle-check.sh :app:assembleDebug :app:testDebugUnitTest
```

Log `.claude/kit/logs/` altında kalır; hata halinde son 80 satır yazdırılır. Helper ignore edildiği için executor worktree’sinde yoksa düz `./gradlew ... --console=plain -q` kullan; `grep | head` pipeline’ını pass/fail kontrolü yapma. Gerçek modül, flavor ve test görevlerini projeden doğrula.

Scaffold varsayılan olarak version snapshot kullanır. Bağımsız olarak en yeni AGP/Kotlin/Gradle sürümlerini seçmek uyumluluk garantisi sağlamadığından canlı çözümleme `--latest` ile opt-in yapılır. Snapshot’ın bu ortamda Android build’i yapılmadı; ilk build baseline’ı senin SDK/JDK üzerinde alınır.

```bash
python3 .claude/kit/scaffold.py --name "My App" --package com.example.myapp --dry-run
# Ağsız kaynak üretimi; wrapper üretmez:
python3 .claude/kit/scaffold.py --name "My App" --package com.example.myapp --offline
# Sonradan eksik wrapper’ı tamamla:
python3 .claude/kit/scaffold.py --name "My App" --package com.example.myapp --wrapper-only
```

Kaynaklar staging alanında üretilir; dosya çakışması varsa hedefe kısmi kaynak ağacı kopyalanmaz. Gradle indirmesi SHA-256 ile doğrulanır ve wrapper’a checksum yazılır. Kısmen var olan wrapper dosyaları ezilmez. Proje adı XML/Android string kurallarına göre escape edilir; SDK aralığı ve paket adı kontrol edilir. Mevcut projelerde screenshot framework’ü/DI/architecture değiştirilmez.

## Seçenekler ve sınırlar

`--dry-run` · `--local-only` · `--no-plugins` · `--no-gateway` · `--extras` · `--with a,b` · `--machine` · `--migrate` · `--no-migrate` · `--reset-routing` · `--no-lsp-install` · `--reinstall-lsp` · `-y`.

`--no-gateway` yerel plugin’i kapatır ve gateway’in kendi unwire komutunu kullanır. Bilinen inherited loopback URL’si yerel doğrudan Anthropic URL’siyle override edilir; **shell’de export edilmiş URL’yi değiştiremez**. Remote Control/compat özel kurulumu veya özel proxy varsa `/kit-doctor` ile etkili ayarları kontrol et. Bu seçenek makine profilindeki GPT rotalarını yeniden yazmaz.

`-y`, LSP indirme teklifini kabul eder; ChatGPT sign-in’i sonraya bırakır. User-scope plugin’leri sessizce kapatmaz: bunun için açıkça `--migrate` gerekir. Tam kurulum local enablement kullanır fakat cache, routing profili, CLI symlink’i ve gerekirse shell PATH ayarı makine seviyesindedir.

## Testler

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -v
python3 tests/check_sidequest.py /gercek/sidequest/bin/sidequest.js
```

İlk suite ağsız fixture’lar kullanır. İkincisi gerçek Sidequest CLI’ıyla geçici izole state içinde profil davranışını kontrol eder; plugin/model çalıştırmaz. Ayrıntılı Türkçe inceleme, gerekçeler ve doğrulama sınırları: `INCELEME-TR.md`.

## 2026.10.03.3 · CLI kontrolü ve Termux

`✓ claude  · node ...` başarılı Claude kurulumu kanıtı değildi: eski sürüm stderr’i gizliyor ve sürüm çağrısının hata kodunu kontrol etmiyordu. Artık makine kurulumu başlamadan önce gerçek `claude --version` sonucu ve kitteki yerel plugin’in validator sonucu kontrol edilir. Exit code ve gerçek hata çıktısı gösterilir. CLI timeout’u 45 saniyedir. Marketplace/installation hataları da görünürdür; hiç plugin geçmezse boş index yazılmaz, son marketplace validasyonu başarısızsa önceki index geri konur.

Script, `~/.local/bin` PATH’e eklenmeden önce bulunan Claude çalıştırılabilir dosyasını kullanır. Başka bir launcher gerekiyorsa komut/argüman dizisi değil, çalıştırılabilir dosyanın tam yolunu ver:

```bash
ANDROID_KIT_CLAUDE_BIN="/tam/yol/claude-wrapper" bash android-kit/setup.sh .
```

Termux yolu görülmesi tek başına hata nedeni değildir; boş sürüm satırı CLI veya wrapper incelemesini gerektirir. Doğrudan Android/Termux, Claude Code’un belgelenen destekli işletim sistemi listesinde yoktur; bu patch tam Termux desteği iddiası taşımaz. Node `process.platform` değerini `android` olarak bildirirse otomatik masaüstü Linux LSP indirmeleri kapatılır. Ubuntu/proot ve native Termux farklı runtime’lardır; JDK, SDK, gateway ve LSP’lerin çalıştığını ayrıca doğrula.

Sorun sürerse şu komutların filtresiz çıktısı asıl nedeni gösterir:

```bash
type -a claude
claude --version
claude plugin validate "$HOME/.claude/local-marketplace/plugins/kotlin-lsp-android"
```

JDK/ANDROID_HOME eksikliği build uyarısıdır; gösterilen manifest hatasının nedeni olarak varsayılmaz. Aynı şekilde Node sürümü bu logdan suçlanamaz.

### --reset-routing ne zaman gerekir?

| Durum | Normal setup davranışı |
|---|---|
| `android-kit` profili yok | Kit rotalarını oluşturur ve uygular. |
| Kitin oluşturduğu profil değiştirilmemiş | Kit güncellemelerini uygular. |
| Profil kullanıcı tarafından değiştirilmiş veya sahiplik kaydı belirsiz | Mevcut profili korur; kit eşlemesini zorlamak için reset gerekir. |
| Board başka kişisel profil kullanıyor | Board seçimi korunur; reset bunu `android-kit`e taşımaz. |
| Board-local category override var | Override etkili kalır; reset bunları kaldırmaz. |

Reset yalnızca ortak `android-kit` profilindeki kit tanımlı kategorileri geri yükler. Profil makine çapında olduğundan onu kullanan diğer board’lar da bu yeni eşlemeyi görür. Bu çalıştırmanın logu makine kurulumunda durduğundan routing aşamasına henüz ulaşılmamıştır.
