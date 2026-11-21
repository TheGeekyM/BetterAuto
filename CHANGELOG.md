# Changelog

Every release has its notes in English and in Italian. `scripts\release.cmd` publishes the English ones on
GitHub, after `docs/release-intro.md`. New entries go under "Unreleased"; a release moves them under its
version and date.

## Unreleased

### English

- Hands-free "hi batata" (Settings > Voice > Wake phrase, off by default): while a car screen is up the phone's
  microphone listens, offline (Vosk with its small English model, nothing is sent), and the phrase starts the voice
  assistant as the mic button does, greeting included. It lets the microphone go during calls, while the assistant
  listens or a message is read, and doesn't count what it hears while BetterAuto's own voice, sounds or a navigation
  prompt play. Wake phrase sensitivity (More options): Strict, Normal, Eager. The recording screen of the earlier
  builds is gone, and the recordings with it. The APK is about 50 MB bigger (the speech model), and the model is
  unpacked once on the phone (about 70 MB) the first time it listens.
- A driving assistant, a few spoken words at the right moment (the assistant's Gemini voice online, else the phone's,
  in Egyptian Arabic or English as the voice language), each one with its own setting:
  - At the start of a drive (not when the car reconnects within 10 minutes): a greeting for the time of day with
    your name, the temperature outside as the car says it, your next appointment today and, when you usually drive
    to work or home at this time, how long the road takes ("صباح الخير يا سام، الجو ٢٤ درجة، وأول ميعاد الساعة ١٠،
    والطريق للشغل ٢٢ دقيقة"). Voice > Arrival brief.
  - After two hours of driving, a gentle reminder to rest, again every 45 minutes; a 15-minute stop starts over.
    Voice > Rest reminder.
  - More than 5 km/h over the road's known limit for 3 seconds: a soft chime (at most once a minute) and the limit
    sign pulses. Map > Speed warning. While it is on, the road under the car is followed for the whole drive, map on
    screen or not.
  - Fuel running low (cars that share their fuel level or range; 15 % or 60 km by default): the nearest station and
    how far it is, "shall I take you?", and the microphone opens: "yes" / "أيوه" adds it as a stop. Vehicle > Low
    fuel help.
  - Where's my car: where the car was left at the end of a drive is kept on the phone only and shown on a card in
    the BetterAuto app with "Navigate on foot"; a quiet notification "Your car is parked" too. Vehicle > Where's my car.
- New messages read aloud in the car (WhatsApp, Telegram, SMS; Settings > Notifications and calls > Read messages
  aloud: Off, Announce only, Read and reply): "Message from Ahmed: …" ("Sara in Family" for groups), in the
  assistant's Gemini voice online or the phone's voice, then "Reply? Say your message" and the microphone once: the
  words go back through the chat's own reply action ("Sent"), "no" or silence sends nothing. Never during a call or
  a navigation prompt (the message waits), verification codes are only announced, the apps are a list to tick.
- Voice replies: "رد على أحمد قوله أنا جاي", "reply to Mario saying I'm late", "rispondi a Giulia che arrivo"
  (also through the online assistant); "read my messages" now offers a reply to the newest one.
- Callers announced by name ("Ahmed is calling. Answer?", Settings > Notifications and calls > Announce callers),
  answered or declined by voice ("أيوه" / "answer", "لا" / "decline") within a few seconds.
- An online voice assistant (Settings > Voice > Voice assistant brain): with Gemini and Anthropic keys of your own
  in the build (`betterauto.geminiKey`, `betterauto.anthropicKey` in local.properties) and internet, clear commands
  still run at once on the phone, and anything else (questions, chat, loose phrasing) goes to Gemini (speech to text)
  and Claude, which runs the same commands or answers briefly in a Gemini voice (female and male voices to choose
  from), in the car's speakers. It remembers the last few exchanges for follow-ups, can search the web for news, and
  falls back to the phone's own recognizer when offline or slow. The listening pill shows whether it may go online.
- The assistant can call you by your name or nicknames (Voice > Your name, set on the phone, kept on the phone) and
  greets you for the time of day.
- Voice commands wait for you to finish: BetterAuto records the microphone itself and its own voice detection decides when the sentence is over, so a pause mid-sentence no longer cuts it (the recognizer is asked again for the rest).
- Pressing play with nothing playing (or "Start music when connected") starts Square when it is on the phone,
  unless a music app is pinned in the bar: then that one.
- Search in the car: "Where to?" > Search opens a keyboard (English or Arabic) on the car screen.
- Better place search: Google Places when the build has a key of its own (`betterauto.placesKey` in
  local.properties), else the phone's geocoder, asked together with OpenStreetMap every time; the phone's geocoder
  keeps only places near you (on Chinese ROMs it answered from China), and the search on the phone looks near
  where the phone last was, not anywhere in the world.
- Voice commands work on phones without offline speech recognition (Xiaomi China ROMs): they use Speech Services
  by Google, or the phone's default speech service. Spoken directions find the phone's voice engine again.
- The music page no longer stays on "Allow notification access" when the phone connects BetterAuto's listener late.
- Routes with the traffic: with a Google key in the build (`betterauto.placesKey`, Routes API enabled) the route
  and its arrival time come from Google's Routes API, Valhalla stays the fallback. Every 5 minutes on the way the
  traffic is checked again: a route by other roads at least 3 minutes quicker is taken, and said.
- Traffic on the route: slow stretches in amber, jams in red (Google). When a trip starts, the other routes are
  drawn in grey and offered for 30 s under the maneuver card, with the time they add and the roads they take.
- Arabic voice, in everyday Egyptian: commands ("خدني للمطار", "كلم أحمد", "شغل عمرو دياب", "غيرها"), replies,
  spoken directions and Google's instructions. The voice language (Auto, English, Arabic, Italian) can now be
  changed in the car's settings too, under Voice.
- Radio widget: Egypt's internet radio stations (every one listed on radio-browser.info, kept on the phone for
  offline starts), drawn like the music player (cover, previous, play/pause, next); the car's media buttons and
  the voice control it while it plays. Radio is also in the app bar: a full page with the player and every station.
  While a station plays its cover comes alive: rings and the art's sound bars pulse.
- The radio no longer takes over the music player: the player (page and widget, cover, lyrics) keeps the song
  that was playing, paused, and its play button brings that song back (the radio stops).
- Voice commands keep the phone's speech app counted as in use while it listens (HyperOS gave it a silent
  microphone), and a language the speech app doesn't have is said as such instead of "I didn't hear anything".
- Covers of streaming players (Square) show: a cover given as a web address is fetched.
- Lyrics tab on the music page, from LRCLIB: timed lyrics follow the song, the line being sung highlighted.
- A third recorded voice, Autonoe (Google Gemini), now the default: Egyptian Arabic only. In English, or in a build
  without her recordings, Soraya speaks for her, a whole sentence at a time.
- Natural recorded voices for the directions and the assistant's replies, in Egyptian Arabic (as Cairo says it) and
  English, shipped in the app and played offline: Settings > Voice > Voice picks Soraya, Nadine or the phone's own voice.
  Every reply is said in that voice: one with a name in it is said without the name ("I can't find that name"),
  the screen shows the whole sentence.
- Saying "nothing", "close", "never mind", "shut up" (or "خلاص", "مش عايز حاجة", "اقفلي", "اسكتي") to the voice
  assistant closes it at once, without a reply; "stop" and "اسكت" still pause the music.
- Voice commands start with a short spoken greeting in the chosen voice (one of five, at random); the microphone
  opens when it ends. Greetings that say the driver's name are kept out of the repository.
- While listening, an orb of light (a port of aidle's Jarvis orb: twelve wobbling loops and drifting dust) weaves in the
  middle of the darkened screen and swells with the voice.
- Voice control of the car through our Open Headunit build (Geely/ECARX): climate on/off, auto, temperature, fan, the
  head unit's volume (up, down, mute), tyre pressures; and "my latest trips" from the recent destinations. Replies in the
  recorded voice; without our head unit app the car is said to be out of reach.
- The car screen also connects to Google's Desktop Head Unit, to try everything on a computer.
- Without Shizuku, BetterAuto's own voice (spoken directions, the assistant's replies and greetings, its chimes)
  now comes from the car's speakers over the lowered music: it no longer brings the music out of the phone while it speaks.
- The map follows a car without a GPS of its own more closely: the phone's GPS runs for the whole drive, not only while
  a map is on screen, and is asked again when it goes quiet; a late or vague phone position (a network guess hundreds
  of metres off, even in the sea) no longer moves the car, an old one is shown as the last known position, and after
  a gap the car goes straight to where it is.
- Music from YouTube no longer cuts out in the car without Shizuku (a few seconds of music, then silence, then
  music again): the phone is now held at its lowest volume step instead of zero, because YouTube mutes its own player
  when the phone's volume is zero, and the car's copy then got silence. A player that still goes quiet whenever the
  phone is muted keeps playing on the phone too instead of being cut every ten seconds.
- The radio and the music no longer play together: tuning in a station pauses the music app (even one that ignores
  losing the audio focus), and starting the music app stops the radio. The car's keys and the voice act on the one
  heard.
- Phone calls no longer show up in the music player and in the car's media status as an empty song: the player keeps
  the song (Teams' call session is ignored too).
- New look for the music and the radio, after the Square player: a near-black page, big section titles, rounded
  covers and a warm amber accent. The music page opens on a music home: what plays next as rows of covers, the songs
  heard lately, the app's library in carousels of cards (its folders also by a face of four covers) and the sources
  (music apps, radio). A floating mini player (cover, title, next, play, a thin progress line) and a floating bar of
  the sections (Home, Up next, Lyrics, Library, Sources) sit at the bottom; the mini player opens the full-screen
  player and Back returns. The library shows albums and playlists as a grid of cards, songs as rows. The radio page
  has the station on air beside the list, and the music's mini player when a song is waiting.
- A new look for the whole car screen, glassy like the Square player, the same in every section (home, bar, music,
  radio, map panels, phone, vehicle, notifications, settings, voice). The song's cover, blurred, lies behind the
  screens; cards are panes of frosted glass with a fine edge and big rounded corners; the app bar, the mini player
  and the panels over the map float as pills of glass; one warm gold marks the knob's ring, what is selected and
  what plays. By night dark glass on near-black with a dark map; by day white glass on a light page (the cover
  washed pale) with a light map. The route stays blue. Warnings turn a deeper orange, so they never read as a
  selection.
- Home widgets: a tap on a widget makes it the main one: it glides into the biggest place, and what was there takes
  its place (kept for that kind of car screen, also after reconnecting). A double tap opens its page (map, music,
  radio, notifications, phone for the favorites, smart home); the widget's own buttons and lists work as before. With
  the knob: a press on a widget makes it main, a second press soon after (or holding it) opens its page. Before, a
  tap on the map or the radio's title opened their page at once.
- Wireless: the car's WiFi can be paired once (Wireless page > The car's WiFi): Android then joins it without its
  "connect?" question every time. BetterAuto no longer tries again and again when Android refuses the car's WiFi
  (the phone's WiFi off, the question cancelled or left unanswered), which only brought the question back and ended
  it: it says why, with a notification to switch the WiFi on or to pair the car's WiFi, and auto-connect waits ten
  minutes (connecting from the phone app still works at once). It waits up to a minute for the answer to Android's
  question. A failed attempt no longer leaves its Bluetooth link to the head unit open.
- Prayer times widget: the next prayer, its time and how long until it, and today's five times, computed on the
  phone (offline) for where the car is, else where the phone last was, else a city chosen in the settings. Egyptian
  General Authority of Survey method by default; Umm al-Qura, Muslim World League, ISNA and Karachi, and Shafi'i or
  Hanafi Asr, under Home screen > More options.
- Azan: at each prayer time, while connected to the car, a short chime and "حان الآن موعد أذان الظهر" (in the voice
  language; the assistant's Gemini voice when online, else the phone's), with the music paused and resumed after.
  Settings > Voice > Azan: chime and voice, chime only, or off.

### Italiano

- "hi batata" a mani libere (Impostazioni > Voce > Parola di attivazione, disattivata di base): mentre è collegata
  un'auto il microfono del telefono ascolta, offline (Vosk con il suo piccolo modello inglese, non si invia nulla), e
  la frase avvia l'assistente vocale come il pulsante microfono, saluto compreso. Lascia il microfono durante le
  chiamate, mentre l'assistente ascolta o si legge un messaggio, e non conta quello che sente mentre suonano la voce
  e i suoni di BetterAuto o un'indicazione del navigatore. Sensibilità della parola di attivazione (Altre opzioni):
  Rigida, Normale, Pronta. La schermata per registrare la frase delle versioni precedenti non c'è più, e le
  registrazioni nemmeno. L'APK è circa 50 MB più grande (il modello vocale), che la prima volta che ascolta viene
  estratto sul telefono (circa 70 MB).
- Un assistente di guida, poche parole al momento giusto (la voce Gemini dell'assistente online, altrimenti quella del
  telefono, in arabo egiziano o in inglese secondo la lingua della voce), ognuna con la sua impostazione:
  - All'inizio di un viaggio (non quando l'auto si ricollega entro 10 minuti): un saluto per l'ora del giorno con il
    tuo nome, la temperatura esterna detta dall'auto, il prossimo appuntamento di oggi e, quando a quest'ora vai di
    solito al lavoro o a casa, quanto ci vuole. Voce > Saluto alla partenza.
  - Dopo due ore di guida, un invito gentile a fare una pausa, poi ogni 45 minuti; una sosta di 15 minuti fa
    ripartire il conto. Voce > Promemoria pausa.
  - Oltre 5 km/h sopra il limite noto della strada per 3 secondi: un suono leggero (al massimo uno al minuto) e il
    cartello del limite pulsa. Mappa > Avviso velocità. Quando è attivo, la strada sotto l'auto è seguita per tutto il
    viaggio, con o senza mappa sullo schermo.
  - Carburante in riserva (auto che condividono livello o autonomia; 15 % o 60 km di base): il distributore più
    vicino e quanto dista, "ti ci porto?", e il microfono si apre: "sì" / "أيوه" lo aggiunge come tappa. Veicolo >
    Aiuto carburante.
  - Dov'è la mia auto: il punto in cui l'hai lasciata alla fine del viaggio resta solo sul telefono ed è in una scheda
    dell'app BetterAuto con "Vai a piedi"; anche una notifica silenziosa "La tua auto è parcheggiata". Veicolo >
    Dov'è la mia auto.
- I nuovi messaggi letti ad alta voce in auto (WhatsApp, Telegram, SMS; Impostazioni > Notifiche e chiamate > Leggi i
  messaggi ad alta voce: No, Solo annuncio, Leggi e rispondi): "Messaggio da Ahmed: …" ("Sara in Famiglia" per i
  gruppi), con la voce Gemini dell'assistente online o con quella del telefono, poi "Vuoi rispondere? Detta il
  messaggio" e il microfono una volta: le parole partono con l'azione di risposta della chat ("Inviato"), "no" o il
  silenzio non inviano niente. Mai durante una chiamata o un'indicazione del navigatore (il messaggio aspetta), i
  codici di verifica sono solo annunciati, le app sono una lista da spuntare.
- Risposte a voce: "rispondi a Giulia che arrivo", "reply to Mario saying I'm late", "رد على أحمد قوله أنا جاي"
  (anche con l'assistente online); "leggi i messaggi" ora propone una risposta al più recente.
- Chi chiama è annunciato per nome ("Ti chiama Ahmed. Rispondi?", Impostazioni > Notifiche e chiamate > Annuncia
  chi chiama), e si risponde o si rifiuta a voce ("rispondi", "rifiuta") entro pochi secondi.
- Un assistente vocale online (Impostazioni > Voce > Cervello dell'assistente vocale): con chiavi Gemini e Anthropic
  tue nella build (`betterauto.geminiKey`, `betterauto.anthropicKey` in local.properties) e internet, i comandi chiari
  restano immediati sul telefono, e tutto il resto (domande, chiacchiere, frasi libere) va a Gemini (trascrizione) e a
  Claude, che esegue gli stessi comandi o risponde in breve con una voce Gemini (voci femminili e maschili tra cui
  scegliere), negli altoparlanti dell'auto. Ricorda gli ultimi scambi per le domande successive, può cercare notizie sul
  web, e torna al riconoscimento del telefono quando è offline o lento. La pillola di ascolto mostra se può andare online.
- L'assistente può chiamarti per nome o soprannome (Voce > Il tuo nome, impostato sul telefono e tenuto sul telefono) e
  ti saluta secondo l'ora del giorno.
- I comandi vocali aspettano che tu abbia finito: BetterAuto registra il microfono da sé e il suo rilevamento della voce decide quando la frase è finita, così una pausa a metà frase non la interrompe più.
- Premere play quando non suona niente (o "Avvia la musica al collegamento") avvia Square se è sul telefono,
  a meno che un'app musicale sia fissata nella barra: allora quella.
- Ricerca in auto: "Dove andiamo?" > Cerca apre una tastiera (inglese o araba) sullo schermo dell'auto.
- Ricerca dei luoghi migliore: Google Places quando la build ha una chiave propria (`betterauto.placesKey` in
  local.properties), altrimenti il geocoder del telefono, interrogati insieme a OpenStreetMap ogni volta; del
  geocoder del telefono restano solo i luoghi vicini (sulle ROM cinesi rispondeva dalla Cina), e la ricerca sul
  telefono cerca vicino a dove il telefono era l'ultima volta, non in tutto il mondo.
- I comandi vocali funzionano anche sui telefoni senza riconoscimento vocale offline (ROM cinesi Xiaomi): usano
  Servizi di riconoscimento vocale di Google, o il servizio vocale del telefono. Le indicazioni vocali ritrovano il
  motore vocale del telefono.
- La pagina della musica non resta più su "Consenti l'accesso alle notifiche" quando il telefono collega tardi il
  listener di BetterAuto.
- Percorsi con il traffico: con una chiave Google nella build (`betterauto.placesKey`, Routes API attiva) il percorso
  e l'ora di arrivo vengono dalla Routes API di Google, Valhalla resta la riserva. Ogni 5 minuti durante il viaggio
  il traffico viene ricontrollato: un percorso per altre strade più veloce di almeno 3 minuti viene preso, e detto.
- Il traffico sul percorso: i tratti lenti in ambra, le code in rosso (Google). All'inizio di un viaggio gli altri
  percorsi sono disegnati in grigio e proposti per 30 s sotto la scheda della manovra, con il tempo in più e le strade.
- Voce in arabo, egiziano di tutti i giorni: comandi ("خدني للمطار", "كلم أحمد", "شغل عمرو دياب", "غيرها"),
  risposte, indicazioni vocali e istruzioni di Google. La lingua della voce (Auto, inglese, arabo, italiano) ora
  si cambia anche dalle impostazioni dell'auto, sotto Voce.
- Widget Radio: le radio via internet dell'Egitto (tutte quelle su radio-browser.info, tenute sul telefono per
  partire anche offline), disegnato come il lettore musicale (copertina, precedente, play/pausa, successiva); anche
  i tasti dell'auto e la voce la controllano mentre suona. La radio è anche nella barra: una pagina intera con il
  lettore e tutte le stazioni.
  Mentre una stazione suona la copertina si anima: anelli e barre sonore del disegno pulsano.
- La radio non prende più il posto del lettore musicale: il lettore (pagina e widget, copertina, testi) resta sul
  brano che suonava, in pausa, e il suo tasto play lo fa ripartire (la radio si ferma).
- I comandi vocali tengono il servizio vocale del telefono "in uso" mentre ascolta (HyperOS gli dava un
  microfono muto), e una lingua che il servizio non ha viene detta come tale invece di "Non ho sentito niente".
- Le copertine dei lettori in streaming (Square) si vedono: una copertina data come indirizzo web viene scaricata.
- Scheda Testo nella pagina della musica, da LRCLIB: il testo sincronizzato segue la canzone, con la riga cantata
  evidenziata.
- Una terza voce registrata, Autonoe (Google Gemini), ora quella predefinita: solo arabo egiziano. In inglese, o in una
  build senza le sue registrazioni, parla Soraya al suo posto, una frase intera alla volta.
- Voci naturali registrate per le indicazioni e le risposte dell'assistente, in arabo egiziano (come si parla al Cairo) e
  in inglese, incluse nell'app e riprodotte offline: Impostazioni > Voce > Voce sceglie Soraya, Nadine o la voce del
  telefono. Ogni risposta è detta con quella voce: una con un nome dentro è detta senza il nome ("Non trovo quel
  nome"), lo schermo mostra la frase intera.
- I comandi vocali iniziano con un breve saluto nella voce scelta (uno di cinque, a caso); il microfono si apre
  quando finisce. I saluti col nome del guidatore restano fuori dal repository.
- Mentre ascolta, un anello di luce si intreccia al centro dello schermo oscurato e accelera con la voce.
- Lo schermo dell'auto si collega anche al Desktop Head Unit di Google, per provare tutto su un computer.
- Senza Shizuku, la voce di BetterAuto (indicazioni, risposte e saluti dell'assistente, i suoi segnali acustici) ora
  esce dagli altoparlanti dell'auto sopra la musica abbassata: mentre parla la musica non esce più dal telefono.
- La mappa segue meglio un'auto senza GPS proprio: il GPS del telefono resta acceso per tutto il viaggio, non solo con
  una mappa sullo schermo, e viene richiesto di nuovo quando tace; una posizione del telefono in ritardo o imprecisa (una
  stima dalla rete a centinaia di metri, anche in mare) non sposta più l'auto, una vecchia appare come ultima posizione
  nota, e dopo una pausa l'auto va subito dov'è.
- La musica di YouTube non si interrompe più in auto senza Shizuku (pochi secondi di musica, poi silenzio, poi di
  nuovo musica): il telefono ora resta al gradino di volume più basso invece che a zero, perché YouTube silenzia il
  proprio lettore quando il volume del telefono è a zero, e la copia per l'auto riceveva silenzio. Un lettore che
  tace comunque quando il telefono è silenziato continua a suonare anche dal telefono invece di essere interrotto
  ogni dieci secondi.
- Radio e musica non suonano più insieme: sintonizzare una stazione mette in pausa l'app musicale (anche una che
  ignora la perdita del focus audio), e avviare l'app musicale ferma la radio. I tasti dell'auto e la voce agiscono
  su quella che si sente.
- Le chiamate non compaiono più nel lettore musicale e nello stato multimediale dell'auto come un brano vuoto: il
  lettore tiene il brano (anche la sessione di chiamata di Teams è ignorata).
- Nuovo aspetto per musica e radio, sul modello del lettore Square: pagina quasi nera, grandi titoli di sezione,
  copertine arrotondate e un accento ambra caldo. La pagina della musica si apre su una home della musica: i prossimi
  brani in righe di copertine, i brani ascoltati di recente, la libreria dell'app in caroselli di schede (le sue
  cartelle anche con un mosaico di quattro copertine) e le sorgenti (app musicali, radio). In basso galleggiano un
  mini lettore (copertina, titolo, successivo, play, una sottile linea di avanzamento) e una barra delle sezioni
  (Home, A seguire, Testo, Libreria, Sorgenti); il mini lettore apre il lettore a schermo intero e Indietro torna.
  La libreria mostra album e playlist come griglia di schede, i brani come righe. La pagina della radio ha la
  stazione in onda accanto all'elenco, e il mini lettore della musica quando c'è un brano in attesa.
- Nuovo aspetto per tutto lo schermo dell'auto, effetto vetro come il lettore Square, uguale in ogni sezione (home,
  barra, musica, radio, pannelli sulla mappa, telefono, veicolo, notifiche, impostazioni, voce). Dietro le schermate
  c'è la copertina del brano, sfocata; le schede sono lastre di vetro smerigliato con un bordo sottile e grandi
  angoli arrotondati; la barra delle app, il mini lettore e i pannelli sulla mappa galleggiano come pillole di
  vetro; un unico oro caldo indica l'anello della manopola, ciò che è selezionato e ciò che suona. Di notte vetro
  scuro su fondo quasi nero e mappa scura; di giorno vetro bianco su fondo chiaro (la copertina sbiadita) e mappa
  chiara. Il percorso resta blu. Gli avvisi diventano di un arancione più deciso, per non sembrare una selezione.
- Widget della home: un tocco su un widget lo rende quello principale: scivola nello spazio più grande, e quello che
  c'era prende il suo posto (resta così per quel tipo di schermo dell'auto, anche dopo una nuova connessione). Un
  doppio tocco apre la sua pagina (mappa, musica, radio, notifiche, telefono per i preferiti, casa intelligente); i
  pulsanti e gli elenchi del widget funzionano come prima. Con la manopola: una pressione su un widget lo rende
  principale, una seconda pressione subito dopo (o tenerla premuta) apre la sua pagina. Prima un tocco sulla mappa o
  sul titolo della radio apriva subito la loro pagina.
- Wireless: il WiFi dell'auto si può abbinare una volta (pagina Wireless > Il WiFi dell'auto): poi Android ci si
  collega senza fare ogni volta la domanda "collegare?". BetterAuto non riprova più di continuo quando Android
  rifiuta il WiFi dell'auto (WiFi del telefono spento, domanda annullata o rimasta senza risposta), cosa che faceva
  solo ricomparire la domanda e la chiudeva: dice il perché, con una notifica per accendere il WiFi o abbinare il WiFi
  dell'auto, e il collegamento automatico aspetta dieci minuti (dall'app sul telefono ci si collega comunque subito).
  Per la risposta alla domanda di Android aspetta fino a un minuto. Un tentativo non riuscito non lascia più aperto il
  suo collegamento Bluetooth con l'autoradio.
- Widget degli orari di preghiera: la prossima preghiera, il suo orario e quanto manca, e i cinque orari di oggi,
  calcolati sul telefono (offline) per dove si trova l'auto, altrimenti dove era il telefono, altrimenti una città
  scelta nelle impostazioni. Metodo dell'Autorità generale egiziana per il rilevamento come predefinito; Umm al-Qura,
  Lega musulmana mondiale, ISNA e Karachi, e Asr Shafi'i o Hanafi, in Schermata home > Altre opzioni.
- Azan: a ogni ora di preghiera, con l'auto collegata, un breve suono e "حان الآن موعد أذان الظهر" (nella lingua della
  voce; la voce Gemini dell'assistente se online, altrimenti quella del telefono), con la musica in pausa e poi ripresa.
  Impostazioni > Voce > Azan: suono e voce, solo suono, o spento.

## 0.3.0 (2026-11-16)

### English

- The home screen is yours to arrange: up to six widgets, with − and + for how many, in rows or columns, moved
  wherever you like (onto another to swap them, beside one, or on a line of their own) and resized, in the car
  with a finger or the knob, or on the phone. Landscape and portrait car screens each keep their own
  arrangement. The earlier one, two or three widget layouts start over from the default.
- Without Shizuku, a player that doesn't let its sound be copied (recent Spotify versions) now plays on the
  phone with a notification saying why, instead of being heard nowhere.
- The app no longer stops when the connection drops while the car's Bluetooth is being set up.
- The car's destinations panel closes by itself when a destination is set on the phone.
- Shizuku now works on phones where it couldn't start BetterAuto's audio service (some ColorOS phones): the
  music, Spotify included, goes to the car there too.
- With Shizuku, the music sometimes stayed on the phone until you reconnected, when the car's Bluetooth was set
  up at the same moment: now it goes to the car.

### Italiano

- La schermata Home la disponi tu: fino a sei widget, con − e + per quanti, in righe o colonne, spostati dove
  vuoi (su un altro per scambiarli, di fianco a uno, o su una riga tutta loro) e ridimensionati, in auto con il
  dito o la manopola, oppure sul telefono. Gli schermi dell'auto orizzontali e verticali hanno ciascuno la
  propria disposizione. Le vecchie disposizioni a uno, due o tre widget ripartono da quella iniziale.
- Senza Shizuku, un player che non lascia copiare il suo audio (le versioni recenti di Spotify) ora si sente
  dal telefono con una notifica che spiega perché, invece di non sentirsi da nessuna parte.
- L'app non si chiude più quando la connessione cade mentre si prepara il Bluetooth dell'auto.
- Il pannello delle destinazioni in auto si chiude da solo quando si imposta una destinazione sul telefono.
- Shizuku ora funziona anche sui telefoni dove non riusciva ad avviare il servizio audio di BetterAuto (alcuni
  telefoni ColorOS): anche lì la musica, Spotify compreso, va all'auto.
- Con Shizuku la musica a volte restava sul telefono finché non ti ricollegavi, quando il Bluetooth dell'auto si
  preparava nello stesso momento: ora va all'auto.

## 0.2.0 (2026-11-13)

### English

- A quick stop with the car switched off and on again keeps the wireless connection: the calls are connected
  again and the music plays again when you drive off.
- After the phone restarts, a notification says when Shizuku isn't running or doesn't allow BetterAuto,
  before you find out in the car.
- "Send diagnostic log" on the phone, to report a problem: what the app and the car did, with no positions,
  contacts or messages, shared with whoever you choose.
- A download less than half the size: the APK is compressed and carries only what phones run (ARM).

### Italiano

- Una sosta breve con l'auto spenta e riaccesa mantiene la connessione senza fili: le chiamate si ricollegano
  e la musica riparte quando riparti.
- Dopo il riavvio del telefono, una notifica avvisa se Shizuku non è avviato o non dà il permesso a
  BetterAuto, prima di scoprirlo in auto.
- "Invia log di diagnostica" sul telefono, per segnalare un problema: cosa hanno fatto l'app e l'auto, senza
  posizioni, contatti o messaggi, mandato a chi scegli tu.
- Un download grande meno della metà: l'APK è compresso e contiene solo ciò che serve ai telefoni (ARM).

## 0.1.0 (2026-11-11)

### English

First public build of BetterAuto, the phone side of Android Auto: its own car launcher, music, calls,
notifications, voice commands, vehicle data, smart home and a map, over USB or wirelessly.

- The phone keeps its sound if the app stops unexpectedly: the audio moved by Shizuku comes back to its
  speaker, and a dropped wireless link no longer crashes the app.
- Problems told by notification: Shizuku stopped, music staying on the phone.
- The music starts by itself on connecting, even while the player is still connecting or the car briefly
  takes the audio back.
- The phone is connected to the car for calls, with retries when the car is slow to answer.
- The progress bar stays readable on grey covers and at night.

### Italiano

Prima versione pubblica di BetterAuto, il lato telefono di Android Auto: il suo launcher per l'auto, musica,
chiamate, notifiche, comandi vocali, dati del veicolo, casa intelligente e una mappa, via USB o senza fili.

- Il telefono non perde l'audio se l'app si chiude all'improvviso: l'audio spostato da Shizuku torna al suo
  altoparlante, e un collegamento senza fili che cade non fa più chiudere l'app.
- I problemi arrivano per notifica: Shizuku fermo, musica rimasta sul telefono.
- La musica parte da sola alla connessione, anche mentre il lettore si sta ancora collegando o l'auto si
  riprende l'audio per un attimo.
- Il telefono viene collegato all'auto per le chiamate, con nuovi tentativi quando l'auto risponde lenta.
- La barra di avanzamento resta leggibile sulle copertine grigie e di notte.
