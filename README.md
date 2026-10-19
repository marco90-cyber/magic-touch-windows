# Magic Touch Remove per Windows

Versione per **Windows** di [Magic Touch Remove](https://github.com/marco90-cyber/Magic-touch-remove): rimuovi oggetti, persone e ombre dalle foto con l'intelligenza artificiale. Tutto gira **sul tuo PC**: le foto non vengono mai inviate a nessun server.

L'interfaccia segue il layout di Photoshop (tema scuro): menu in alto, barra delle opzioni, strumenti a sinistra, tela al centro, pannelli a destra (Proprietà, Modelli IA, Storia) e barra di stato.

## Funzioni
- **Selezione automatica** (MobileSAM o SAM 2): clic su un oggetto o trascina un riquadro.
- **Pennello**, **Lasso**, **Gomma** e **Ripristina**, con dimensione e durezza regolabili.
- **Cancella** con LaMa (preciso) o MI-GAN (veloce); modalità **AUTO** che parte da sola.
- **Storia** con tutti i passaggi, annulla/ripeti, **Confronta** con l'originale (tieni premuto).
- Zoom con la rotellina, spostamento con lo strumento Mano o il tasto centrale/destro del mouse.
- Salvataggio a piena risoluzione in PNG o JPEG. L'originale non viene mai toccato.

## Scorciatoie
| Tasto | Azione | Tasto | Azione |
|---|---|---|---|
| `W` | Selezione automatica | `Ctrl+O` | Apri |
| `B` | Pennello | `Ctrl+S` | Salva con nome |
| `L` | Lasso | `Ctrl+Z` / `Ctrl+Maiusc+Z` | Annulla / Ripeti |
| `E` | Gomma | `Ctrl+D` | Deseleziona |
| `R` | Ripristina | `Ctrl+0` / `Ctrl+1` | Adatta / 100% |
| `H` | Mano | `[` `]` | Dimensione pennello |
| `Invio` | Cancella con l'IA | | |

## Installazione
Scarica dalle [Releases](../../releases/latest) il file **.msi** (installer) oppure lo **zip portatile** (decomprimi e avvia `MagicTouchRemove.exe`). Al primo avvio scarica i modelli dal pannello «Modelli IA» (LaMa + MobileSAM, circa 236 MB; poi funziona offline). I modelli sono quelli già pubblicati nelle Releases di Magic Touch Remove.

**Requisiti**: Windows 10 o 11 a 64 bit, 8 GB di RAM consigliati.

## Compilare
Serve JDK 17+. `gradle run` avvia l'app; `gradle createDistributable` crea la cartella portatile; `gradle packageMsi` l'installer (richiede WiX 3). La build per Windows si fa da sola con GitHub Actions a ogni push su `main`.

## Licenze
Codice sotto licenza MIT. Modelli: LaMa (Apache-2.0), MI-GAN (MIT), MobileSAM (MIT), SAM 2 (Apache-2.0). Librerie: ONNX Runtime (MIT), Compose Multiplatform (Apache-2.0).
