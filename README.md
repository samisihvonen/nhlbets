# NHL Prediction & Value Analytics (Spring Boot + React)

Päätöstuki- ja tutkimustyökalu: hakee oikeaa NHL-dataa, laskee otteluille todennäköisyydet (Poisson-pistemalli),
vertaa niitä kertoimiin (EV, edge), selvittää ennusteet automaattisesti ja mittaa oman tarkkuutensa.
Ei vedonlyöntiä, panoksia, bankrollia eikä euromääräisiä tuloksia.

## Rakenne
- `backend/` Spring Boot 3.3 (Java 17+), Spring Data JPA, H2 (tiedostotietokanta `backend/data/`)
  - `core/` puhdas Java: `ScoreModel`, `MarketMath`, `Metrics`, `Backtest`, `GameResult`, `TeamNames`
  - `provider/` `NhlClient`, `OddsApiProvider` (The Odds API), `Veikkaus`/`Paf` (EI integroitu, raportoi tilansa)
  - `service/` `RefreshService` (haku, ennusteet, selvitys), `MarketService`, `AnalyticsService`, `SchedulerJob`
  - `web/ApiController` REST-rajapinta `/api/...`
- `frontend/` React + Vite + Tailwind (tumma teema, aikavyöhyke Europe/Helsinki)

## GitHub
`.env` (avaimet), `backend/data/` (tietokanta, voi sisältää kassa- ja vetotietoja), `node_modules/` ja ladatut CSV-aineistot on suljettu pois `.gitignore`-tiedostolla.
Frontendin build (`backend/src/main/resources/static/`) generoidaan komennolla `npm run build`, joten sitä ei versioida. Älä koskaan committaa API-avaimia; jos avain vuotaa, vaihda se heti.

## Sivut
Dashboard on kolmisivuinen (osoitteet `#/dashboard`, `#/suggestions`, `#/history`):
- **Yleiskatsaus:** tilastot, moneyline- ja 1X2-ennusteet, yhdistelmät, tarjoajien tila.
- **Ehdotukset:** moneyline- ja 1X2-ehdokkaat järjestettynä näytön vahvuuden (z = EV / keskivirhe) mukaan, perusteluineen. `GET /api/suggestions`.
  Ehdokkaat eivät ole suosituksia: kun malli on kokeellinen, suositus on `NO_RECOMMENDATION_EXPERIMENTAL_MODEL`. Tyhjä lista on usein oikea tulos.
- **Historia:** ennusteet ja selvitykset (suodatus päivän ja tilan mukaan: kaikki/selvitetyt/odottavat/mitätöidyt), tarkkuus ja kalibrointi, CLV, CSV-tuonnit ja backtestit. `GET /api/history?status=`.

## Ajaminen (Windows / Linux)
Vaatii: JDK 17+, Maven 3.9+, Node 18+.

    # 1) Frontend -> buildataan backendin static-kansioon
    cd frontend
    npm install
    npm run build

    # 2) Backend (luo tietokannan automaattisesti)
    cd ../backend
    mvn spring-boot:run

Avaa http://localhost:8080 ja paina "Refresh Data & Settle Predictions". Ensimmäinen päivitys hakee
kauden otteluhistorian (`BACKFILL_FROM`) viikko kerrallaan, joten se kestää hetken.

Kehitystila: `mvn spring-boot:run` + `cd frontend && npm run dev` (http://localhost:5173, proxy -> 8080).

Valinnaiset asetukset: kopioi `.env.example` nimelle `.env` (projektin juureen tai `backend/`-kansioon), täytä `ODDS_API_KEY=avaimesi` ja **käynnistä sovellus uudelleen**.
Vaihtoehtoisesti ympäristömuuttuja samassa terminaalissa kuin `mvn spring-boot:run`: PowerShell `$env:ODDS_API_KEY="avaimesi"`, Linux `export ODDS_API_KEY=avaimesi`.
Tarkista tila: http://localhost:8080/api/health, tarjoajan `the-odds-api` tilan pitää olla CONFIGURED.

## Testit ja backtest
    cd backend && mvn test              # JUnit: EV, normalisointi, 1X2/ML, jatkoaika, leakage, mallin todennäköisyydet
    curl -X POST "http://localhost:8080/api/backtest?warmup=100"   # walk-forward, vain malli (ei historiallisia kertoimia)

## API
GET `/api/health`, `/api/games`, `/api/predictions/moneyline`, `/api/predictions/regulation`, `/api/combinations`,
`/api/lineups`, `/api/stats/summary`, `/api/stats/accuracy?from=&to=`, `/api/history`, `/api/clv`, `/api/xg`;
POST `/api/refresh`, `/api/backtest`, `/api/backtest/market`, `/api/import/odds`, `/api/import/xg`.

## Mallin tulkinta
- Mallitodennäköisyys on estimaatti, ei varmuus. Ennusteet ovat **kokeellisia**, kunnes vähintään
  `MIN_SETTLED_FOR_VALIDATION` ennustetta on selvitetty ja kalibraatio on tarkistettu.
- EV % = 100·(p·kerroin − 1). Edge = malli − normalisoitu markkinatn. (prosenttiyksikköä). Ne mittaavat eri asioita.
- Osumatarkkuus ei osoita arvoa. Vertaa Brier/log loss -lukuja tasajakaumaan (ML 0.500 / 0.693, 1X2 0.667 / 1.099).
- Moneyline (sis. jatkoaika/SO) ja 60 min 1X2 pidetään aina erillään.

## CLV-analyysi, historialliset kertoimet ja xG

### CLV (Closing Line Value)
`GET /api/clv` (ja dashboardin "CLV-analyysi") vertaa mallin **liputettujen valintojen** hintaa (paras hinta ennen alkua tallennetussa
snapshotissa) vertailutarjoajan sulkeutumislinjaan (oletus `pinnacle`, asetus `CLV_REFERENCE_PROVIDER`).
- CLV % = (otettu kerroin / sulkeutumiskerroin − 1) · 100. Lisäksi sulkeutumis-EV %, jossa marginaali poistetaan **koko sulkeutumismarkkinasta**
  (power-menetelmä), ei oletetulla kiinteällä 3 % marginaalilla.
- Tulokset jaetaan markkinoittain ja **ajankohdan mukaan** (<3 h, 3–12 h, ≥12 h ennen alkua). Aloittavia maalivahteja ei vielä tunneta,
  joten aikaisten ennusteiden CLV on tyypillisesti vääristynyt: lue ryhmät erikseen.
- Mukana on mallin Brier-vertailu sulkeutumislinjaan selvitetyillä peleillä.
- Sulkeutumislinja hyväksytään vain, jos se on enintään 180 min ennen alkua ja markkina on täydellinen. Muuten peliä ei lasketa mukaan.
- Mitään oikeaa vetoa ei ole: "otettu hinta" on hypoteettinen. Paras hinta kaikista tarjoajista vs. yhden tarjoajan sulkeutuminen imartelee CLV:tä.

### Markkinat
Oletuksena haetaan vain `h2h` (moneyline, `ODDS_MARKETS=h2h`). Jos haluat kokeilla 60 min 1X2 -kertoimia, aseta `ODDS_MARKETS=h2h,h2h_3_way`; jos tarjoaja hylkää sen (HTTP 422), sovellus yrittää automaattisesti uudelleen pelkällä `h2h`:lla ja kertoo syyn päivitysraportissa. Ilman 3-way-kertoimia 1X2-sarake näyttää MARKET_UNAVAILABLE eikä sitä arvioida.

### Yhdistelmäehdotukset
`GET /api/combinations?maxLegs=2|3` yhdistää positiivisen EV:n valintoja **eri otteluista** (saman ottelun valintoja ei yhdistetä). Kullekin yhdistelmälle näytetään osumatodennäköisyys virhemarginaalilla,
yhdistetty kerroin, mallin mukainen reilu kerroin, EV ± keskivirhe, z-luku, "näyttö" (WEAK/LOW/MODERATE) ja **EV jos markkina on oikeassa** (osoittaa, miten marginaali kertautuu).
Toiminto ei lupaa tuottoa: osumatodennäköisyys laskee jokaisella valinnalla, riippumattomuus on oletus, keskivirhe ei sisällä mallivirhettä ja listan paras yhdistelmä on valikoitumisharhan vuoksi yliarvioitu.
Kun malli on kokeellinen, suositus on aina `NO_RECOMMENDATION_EXPERIMENTAL_MODEL`.

### Kertoimien lähteet ja skräppäys
**Sovellus ei skräppää vedonvälittäjien tai kerroinsivustojen verkkosivuja.** Se rikkoisi lähes varmasti käyttöehtoja (Veikkaus ja Paf kieltävät sen), vaatisi
bot-suojausten kiertämistä (esim. Cloudflare) ja olisi hauras. Sen sijaan kertoimet tulevat näistä lähteistä:
- **The Odds API** (`ODDS_API_KEY`): ilmainen kuukausikiintiö, moneyline.
- **OddsPapi** (`ODDSPAPI_KEY`): ilmainen kehittäjäavain, aggregaattori; moneyline (markkina 151) NHL-otteluille. Skeema perustuu toimittajan omaan dokumentaatioon, eikä sitä ole testattu livenä.
  `/api/health` listaa viimeksi nähdyt vedonvälittäjät (esim. onko `pinnacle` tai `paf` mukana). Rajaa kirjat asetuksella `ODDSPAPI_BOOKMAKERS`.
- **Käsin syöttö** (Ehdotukset-sivu tai `POST /api/odds/manual`): kirjoita Veikkauksen tai Pafin kertoimet itse. Tallentuu tarjoajalle `manual:<nimi>`.
- Veikkaus ja Paf: ei dokumentoitua julkista rajapintaa löytynyt, joten ne pysyvät tilassa NOT_INTEGRATED.

### Kerroinkiintiö (tärkeää)
The Odds API:n ilmaisversiossa on kuukausikiintiö (arviolta ~500 krediittiä; kunkin kyselyn hinta riippuu markkinoiden ja alueiden määrästä, tämä sovellus
pyytää 2 markkinaa). Siksi kertoimet haetaan oletuksena enintään **`ODDS_MIN_INTERVAL_MINUTES=240`** minuutin välein vaikka päivitys ajetaan 30 min välein.
Jäljellä oleva kiintiö näkyy `/api/health`-vastauksessa ja dashboardin tarjoajatilassa.
`CLOSING_CAPTURE=true` kerää kertoimia 10 min välein tuntia ennen pelejä, mikä kuluttaa kiintiön nopeasti; käytä vain maksullisella paketilla.
Historiallista kerroinrajapintaa tämä sovellus ei käytä (sen saatavuus ilmaispaketissa on epävarma, tarkista tarjoajan hinnoittelu).

### Historialliset kertoimet CSV:stä (esim. Kaggle/SBR)
    curl -X POST -F "file=@nhl_odds.csv" "http://localhost:8080/api/import/odds?format=american&source=pinnacle"
Pakolliset sarakkeet: `date` (yyyy-MM-dd), `home_team`, `away_team`, `home_ml_close`, `away_ml_close`; valinnaiset `home_ml_open`, `away_ml_open`.
`format=american` muuntaa kertoimet (+110 → 2.10, −130 → 1.77), `format=decimal` käyttää arvoja sellaisenaan. Joukkueet täsmätään nimillä, lyhenteillä ja
yleisillä aliaksilla (NYRangers, LA Kings, Tampa Bay). Ottelun on oltava jo tietokannassa (aja ensin päivitys; `BACKFILL_FROM` määrää aloituspäivän).
Vastaus kertoo täsmäytetyt, täsmäämättömät ja monitulkintaiset rivit. Tarkista aineiston lisenssi. OddsPortal-skräppäystä ei ole toteutettu, koska se
rikkoo todennäköisesti sivuston ehtoja.

### Markkinabacktest
    curl -X POST "http://localhost:8080/api/backtest/market?warmup=100&minEv=3&source=pinnacle"
Walk-forward: malli ennustaa vain aiempien päivien datalla ja sitä verrataan sulkeutuvan moneylinen no-vig-todennäköisyyteen (Brier, log loss, parivertailun keskivirhe).
Lisäksi raportoidaan liputettujen valintojen hypoteettinen tasapanosyksikkötuotto sulkeutumishinnoilla. Se ei ole kannattavuusväite.
**Varoitus otoskoosta:** synteettisessä testissäni 700 pelin otos näytti +10 % "tuottoa" markkinaa vastaan, mutta 12 000 pelin otoksessa sama malli hävisi
markkinalle (−4…−7 %). Pienet otokset valehtelevat; luota vain luottamusväleihin ja tuhansiin havaintoihin.

### xG
    curl -X POST -F "file=@teams.csv" "http://localhost:8080/api/import/xg?season=2025"
Odottaa MoneyPuck-tyylistä joukkue-CSV:tä (sarakkeet `team, games_played, xGoalsFor, xGoalsAgainst, goalsFor, goalsAgainst`, valinnaisesti `season, situation`;
vain rivit `situation=all` luetaan; koodit T.B/N.J/S.J/L.A muunnetaan NHL-lyhenteiksi). Lataa data lähteen omalta sivulta ja tarkista käyttöehdot.
xG näytetään ennusteiden yhteydessä **vain kontekstina**: kausitason xG ei ole ajanhetkikohtaista, joten sen käyttö mallissa tai backtestissä vuotaisi tulevaa tietoa.
Mallin laajentaminen xG:llä vaatii ottelukohtaista dataa ja ablaatiotestin.

## Rajoitukset (rehellisesti)
- NHL-rajapinnan kenttänimet on toteutettu julkisen skeeman mukaan, mutta **ei ole testattu livenä** tässä ympäristössä.
  Jos jokin kenttä poikkeaa, ensimmäinen päivitys raportoi virheen `NhlClient`-vaiheessa.
- Veikkaus ja Paf: dokumentoitua, sallittua julkista rajapintaa ei ole; moduulit ovat tarkoituksella pois käytöstä.
- CLV, CSV-tuonti, markkinabacktest ja xG-tuonti on toteutettu, mutta Spring-osia ei ole käännetty eikä ajettu kehitysympäristössä; ydinlaskenta (CLV, devig, CSV, markkinabacktest) on ajettu.
- Kokoonpano-, loukkaantumis- ja maalivahtilähdettä ei ole integroitu: tila on UNKNOWN eikä vaikuta malliin.
- Tietokantaskeema luodaan Hibernaten `ddl-auto: update` -asetuksella (ei versioituja migraatioita, esim. Flyway).
- H2 käytössä SQLiten sijaan (luotettavampi JPA-tuki). Varmuuskopio: sulje sovellus ja kopioi `backend/data/`.
- Mallin xG-, rasitus- ja pelaajavaikutukset puuttuvat; Poissonin riippumattomuusoletusta ei ole validoitu,
  OT/SO-voittajan mallinnus on heuristinen. Yhdistelmät olettavat riippumattomuuden eri otteluiden välillä.
- Ei CSRF-suojausta: API on evästeetön ja tarkoitettu paikalliseen käyttöön. Lisää autentikointi ennen julkaisua.
