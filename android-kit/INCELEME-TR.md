# İnceleme ve değişiklik notları

İncelenen kaynak: `android-kit-4.zip`. İlk teslim sürümü: **2026.10.03.2**, takip düzeltmesi: **2026.10.03.3**, 3 Ekim 2026.

## Genel değerlendirme

Kurgunun güçlü tarafı işlerin kategorilere ayrılması: ana oturum planlıyor, executor’lar sınırlı kapsamda çalışıyor, verify ve gerekirse bağımsız review sonrası entegrasyon yapılıyor. Fork için önce baseline çıkarma, mevcut hatayı yeni değişiklikten ayırma, UI/theme işlerini sıraya koyma ve kişisel routing seçimlerini koruma doğru kararlar.

En büyük eksikler plugin sayısında değil, kurulumun tekrar çalıştırılması ve otomatik kontrollerin doğruluğundaydı. Bazı yerlerde README’nin verdiği güvence kodun sağladığından güçlüydü. Bu sürüm esas olarak bu farkları kapatır.

## Düzeltilen somut sorunlar

| Öncelik | Eski davranış / risk | Yeni davranış |
|---|---|---|
| Yüksek | `CLAUDE.upstream.md` zaten varsa mevcut CLAUDE.md yedeksiz ezilebiliyordu. | Her kurulumdan önce timestamp’li snapshot; iki belgenin içeriği korunuyor. |
| Yüksek | Manifest’te bulunan kit dosyası, kullanıcı düzenlemiş olsa bile her seferinde üzerine yazılıyordu. | Hash karşılaştırması; değişen dosya korunuyor, yeni şablon incoming klasörüne ayrılıyor. |
| Yüksek | `--no-plugins` yine makine kurulumuna, marketplace işlemlerine ve CLI bağımlılığına giriyordu. | Gerçekten proje dosyalarıyla sınırlı, ağsız mod. |
| Yüksek | `has_dep` fonksiyonundaki `grep | head`, eşleşme yokken başarı sayılabiliyordu. | Python ile doğrudan eşleşme; boş projede DI/DB/network/screenshot sonucu `none`. |
| Yüksek | Gradle çıktısındaki `grep | head` pipeline’ı başarı/hata sinyalini değiştirebiliyordu. | Gerçek Gradle exit code’u döndüren helper, tam log ve sınırlı hata özeti. |
| Yüksek | UI guard yalnızca öneksiz ajan adını tanıyordu. | `sidequest:` namespace normalizasyonu, göreli yol çözümü ve Preview tespiti. |
| Yüksek | Settings migration tek bir kit hook’u gördüğünde aynı gruptaki kullanıcı hook’unu da silebiliyordu. | Handler bazında, tanınan eski tanım üzerinden işlem; diğer handler’lar kalıyor. |
| Yüksek | Eksik commit pin’i default branch’e sessizce düşebiliyordu. | Pin bulunamazsa ilgili plugin atlanır ve uyarı verilir; sessiz sürüm değişimi yok. |
| Orta | `.git/info/exclude` sabit yolu linked worktree’de bozuluyordu. | `git rev-parse --git-path info/exclude`; gerçek worktree fixture’ıyla kontrol edildi. |
| Orta | Repo alt dizininde veya riskli/symlink yönetim yollarında yanlış kurulum mümkün oluyordu. | Ön kontrol, yol doğrulama ve kurulum kilidi. |
| Orta | Plugin hataları yalnızca uyarıydı, setup başarılı sayılabiliyordu. | `failed_plugins` kaydı ve kısmi kurulumda exit code `3`. |
| Orta | `--no-gateway` daha önce kurulmuş yerel gateway’i etkin bırakabiliyordu. | Yerel disable, upstream unwire komutu ve bilinen inherited URL kontrolü. Shell override sınırı açıkça belgeli. |
| Orta | `-y` makine genelindeki plugin migration’ına da sessiz onay verebiliyordu. | User-scope migration için açık `--migrate`; normal kişisel kurulum local kalıyor. |
| Orta | Tek compaction dosyası eşzamanlı oturumlarda karışabiliyor, transcript tümüyle belleğe alınıyordu. | Oturum kimliğine göre dosya, alt ajanı dışlama, akış halinde okuma, sınırlı geçmiş alıntıları. |
| Orta | PostToolUse formatter `jq` kullanıyor ama bağımlılığı kontrol edilmiyordu. | Python formatter helper; yol denetimi ve timeout. |
| Orta | Yeni projede app adındaki `&`, `<`, tırnak gibi karakterler XML’i bozabiliyordu. | Android string/XML escape; SDK aralığı ve paket anahtar kelime kontrolü. |
| Orta | Scaffold ağ/çakışma hatasında kısmi proje bırakabiliyor; `--offline` wrapper indiriyordu. | Staging, dosya çakışmasında hedefi koruma, gerçek offline kaynak üretimi, sonradan wrapper tamamlama. |
| Orta | Gradle dağıtımı checksum’suz indiriliyordu, kısmi wrapper dosyası ezilebiliyordu. | SHA-256 doğrulaması ve wrapper checksum ayarı; mevcut wrapper parçasını ezmeme. |
| Orta | Her bileşeni bağımsız “latest” seçmek birlikte çalıştıklarını garanti etmiyordu. | Varsayılan version snapshot, `--latest` opt-in. İlk build hâlâ zorunlu. |
| Orta | Bootstrap `git add -A` ile alakasız değişiklikleri de commit’e alabiliyordu. | Önce index/status inceleme, yalnızca ilgili dosyaları açık yollarla stage etme. |
| Orta | Bootstrap çalışan branch yerine remote default branch’i dayatabiliyordu. | Amaçlanan entegrasyon branch’ini koruma; gerçek uyumsuzlukta karar alma. |
| Orta | Mevcut projeye `:app`, `Debug`, Compose screenshot, version catalog gibi varsayımlar taşınıyordu. | Gerçek modül/flavor/framework keşfi; mevcut mimari, DI, sürümleme ve test altyapısı korunur. |
| Orta | Manifest normalizer geçerli hook dizilerini silebiliyordu. | Path/inline/mixed array desteği; tanınmayan hook validator’a bırakılır. |
| Düşük | Opsiyonel web plugin’leri seçilmeden de ilk makine kurulumunda klonlanıyordu. | İlk indirme opt-in; başka projelerin mevcut clone’ları korunur. |

## Ajan ve efor seçimlerine yorumum

Bu bölüm kontrollü bir benchmark sonucu değil, görev türüne göre mühendislik değerlendirmesidir. Kullanıcının UI’yi Claude’da tutma tercihi korunmuştur.

- **Luna medium:** mekanik kod ve çok sınırlı açıklamalar için uygun bir başlangıç. Test yazımı daima mekanik değildir: yanlış bir assertion’ın yeşil kalması gerçek bug’ı gizleyebilir. Bu nedenle yeni test tasarımı/onarımı `behavior-verification` altında **Sol high** oldu. Var olan komutu çalıştırıp sonucu raporlamak için yeni **`test-execution` → Luna medium, read-only** kategorisi eklendi.
- **Sol high:** normal implementation, debugging, spike ve codebase exploration için korundu. Özellikle büyük fork’ta keşfi otomatik medium’a indirmedim; yanlış repo modeli sonraki tüm işleri etkiler. Küçük tanıdık repolarda bu kategori kişisel olarak medium’a indirilebilir.
- **Astra high:** gerçekten belirsiz tasarım, zor problem ve plan-audit için korundu. Bütün normal coding’i Astra’ya taşımak gerekmiyor. Kendi ölçümün kalite farkı göstermiyorsa hard rotada Sol xhigh denenebilir; önceki README’deki desteklenmemiş kesin fiyat/benchmark karşılaştırmasını kaldırdım.
- **Opus high:** ana orkestratör, önemli UI ve bağımsız kod denetimi için korundu. Ana oturum varsayılanı önce yalnızca README’deydi; şimdi yerel ayarlarda da bulunuyor. Kullanıcının mevcut yerel model/efor değeri ezilmiyor.
- **Opus medium:** görsel review’un gerçek ayarı buydu; eski README’de high gösterilen tablo düzeltildi. Görüntü değerlendirmesini otomatik xhigh yapmadım.
- **Sonnet medium:** mekanik UI ve kaynak araştırması için yeterli bir varsayılan olarak korundu. Zorlu teknik araştırma sonucu belirsizse daha güçlü rota seçilebilir; her URL araması için pahalı model gerekmez.
- **Opus xhigh → Fable xhigh:** son çare zinciri korundu. Ancak farklı hipotez denemeyen iki tekrarı iki anlamlı girişim sayma. Auth/SDK/network hatasını model yükselterek çözmeye çalışma. Fable hesabında erişilebilir değilse yerel katalog/hesap durumunu kontrol et.

`.frontier` isimleri gereksiz karmaşıklık gibi görünse de Sidequest 5.6.0 kaynaklarında gerçekten gerekçesi var. `category-defaults.ts`, Astra rotalarını bu işaretin olmadığı kategorilerde Sol’a geçiriyor. Gerçek CLI testinde `.frontier` rotalarının kalıcı olduğu doğrulandı.

## Plugin ekleme kararı

Yeni bir zorunlu plugin eklemedim. Pakette zaten orchestration, mapping, rules, Android/KMP, release, emulator, güvenlik ve dokümantasyon katmanları var. En büyük getiri burada güvenilir kurulum ve doğru doğrulamadan geliyor.

`observability` zaten opsiyonlar arasında. Sana özel model/efor ayarı yapmak için en yararlı mevcut seçenek bu: `android-kit --with observability`. Birkaç projede ticket türü, toplam süre, tekrar sayısı, review reddi ve kullanım miktarını birlikte karşılaştır. Sadece tek isteğin hızına göre karar verme. Telemetriyi varsayılan açmadım.

`design-with-claude` web ağırlıklı olduğu için Android varsayılanına alınmadı. İkinci bir orchestration veya memory plugin’i eklemek de Sidequest/live-rules/auto-memory ile çakışabilir. Context7 ve LSP’lerin varlığı derleme/test ihtiyacını ortadan kaldırmaz.

## Doğrulama

1. **32 ağsız regresyon testi geçti.** Python unittest suite’i: boş proje, tekrar kurulum, yerel exclude, dolu upstream adı, kişisel dosya değişiklikleri, eski manifest, bozuk JSON, yol taşması/symlink, repo alt dizini, gerçek linked worktree, settings merge, bağımlılık tespiti, Gradle exit code’u, UI/map guard, offline scaffold, XML, session isolation, hook manifest dizileri ve local-scope/kısmi plugin hatası senaryoları.
2. Gerçek Sidequest **5.6.0**, upstream commit **03ef4050f75f549f03f8c1531b5cc1516fb90621**: profil oluşturma, idempotence, board binding, Astra persistence, stock hard tier disable, test rotası ayrımı, katalogda GPT bulunmadığında Claude fallback, kişisel profil düzenlemesini koruma, açık reset ve başka board profilini koruma geçti.
3. Shell söz dizimleri ve Python/JSON dosyaları kontrol edildi. Testler paketin içindedir; tek komutla tekrar çalıştırılabilir.

**Doğrulanmayanlar:** Bu ortamda Claude CLI ile gerçek plugin install/validate, ChatGPT OAuth, çalışan Model Gateway isteği, JDK/Android SDK ile Gradle/AGP derlemesi, emulator ve LSP binary indirme/çalıştırma yapılmadı. Plugin scope/hata akışı kontrollü sahte CLI ile sınandı. Topluluk plugin pin’lerinin uzak erişilebilirliği bu çalışmada doğrulanamadı; mevcut pin’ler korunmuştur. Scaffold version snapshot’ı orijinal kitten taşınmıştır, bu değişiklikte build edilmiş bir kombinasyon olarak sunulmaz.

UI guard keyfî shell yazmalarını engelleyen tam bir sandbox değildir. Hook’lar ve `Read(...)` kuralları bütün araç yolları için genel sır koruması sayılmamalı. Existing tracked dosyalar `.gitignore` veya `--local-only` ile görünmez olmaz. Makine seviyesindeki cache/routing kurulumuyla proje local scope aynı kavram değildir.

## Yükseltme sırası

1. Arşivi aç; mevcut proje kökünden yeni `setup.sh --dry-run .` çalıştır.
2. Normal proje için `setup.sh .`, fork katkısı için `setup.sh --local-only .` çalıştır.
3. Claude’u tamamen yeniden başlat; `/bootstrap` çalıştır. `incoming` farklarını mevcut proje kurallarını koruyarak birleştir.
4. `/kit-doctor`, sonra gerçek build/test baseline’ı.
5. Sidequest profilini kişisel olarak düzenlediysen otomatik korunur. Yeni test ayrımını da kitin tamamı ile uygulamak istersen `--reset-routing` kullan; bu seçenek ortak profilde kitin tanımladığı kategorileri geri yükler; board override ve ekstra kategoriler kalır.

## Birincil kaynaklar

- Claude plugin manifest, validasyon ve hook şekilleri: https://code.claude.com/docs/en/plugins-reference
- Hook input ve plugin namespace’leri: https://code.claude.com/docs/en/hooks
- Model alias’ları, model ayarı ve efor: https://code.claude.com/docs/en/model-config
- Eigenwise kaynakları: https://github.com/Eigenwise/eigenwise-toolshed
- İncelenen sabit kaynak: https://github.com/Eigenwise/eigenwise-toolshed/tree/03ef4050f75f549f03f8c1531b5cc1516fb90621/plugins/sidequest

Kaynaklar schema/CLI davranışını doğrulamak için kullanıldı. Bu rapordaki model tercihleri, kullanıcının iş akışına yönelik önerilerdir; sağlayıcı performans garantisi değildir.

## Takip düzeltmesi · 2026.10.03.3

Kullanıcının Termux logunda Claude sürümü boş, bütün yerel plugin validasyonları başarısızdı. Önceki sürüm stderr’i gizlediği için gerçek CLI/manifest hatası bilinmiyor. Düzeltme: çalışma öncesi CLI/validator kontrolü, gerçek hata çıktısı ve exit code, çağıranın seçtiği executable’ı koruma, `ANDROID_KIT_CLAUDE_BIN`, native Android’de otomatik Linux LSP indirmesini atlama, boş marketplace index’i yazmama ve başarısız index doğrulamasında eski index’e dönme. Bu bir teşhis/kurulum akışı düzeltmesidir; kullanıcının cihazındaki altta yatan CLI sorununun çözüldüğü doğrulanmış değildir.

CLI’ın hata vermesi, boş version döndürmesi, version’ı stderr’e yazması ve validator hatasını aktarması için dört fixture eklendi. Toplam **36 regresyon testi**. Routing davranışı değiştirilmedi; README’de profil/board/override ayrımı açıklandı.

Kaynak: https://code.claude.com/docs/en/setup
