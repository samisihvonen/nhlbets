package fi.nhl.analytics.service;

import fi.nhl.analytics.core.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Read-only transparent daily plan. A plan is decision support, never an instruction to bet. */
@Service
public class MatchdayPlanService {
    private static final ZoneId HELSINKI = ZoneId.of("Europe/Helsinki");
    private final MarketService market; private final BankrollService bankroll;
    public MatchdayPlanService(MarketService market, BankrollService bankroll) { this.market = market; this.bankroll = bankroll; }
    public Map<String,Object> plan(LocalDate day) {
        LocalDate date = day == null ? LocalDate.now(HELSINKI) : day;
        BankrollService.State state = bankroll.state(); boolean experimental = market.experimental();
        List<String> gates = new ArrayList<>(); if (experimental) gates.add("MODEL_EXPERIMENTAL");
        gates.add("VALIDATED_EDGE_REQUIRED"); // no market-backtest/CLV gate exists yet, therefore no live stake can be created.
        List<Map<String,Object>> source = (List<Map<String,Object>>) market.suggestions().get("items");
        List<Map<String,Object>> rows = new ArrayList<>(); List<Staking.Candidate> candidates = new ArrayList<>(); Map<String,Map<String,Object>> byId = new HashMap<>();
        for (Map<String,Object> s : source) {
            Instant at = (Instant) s.get("startUtc"); if (!at.atZone(HELSINKI).toLocalDate().equals(date)) continue;
            String id = s.get("gameId") + ":" + s.get("market") + ":" + s.get("selection"); Double odds=(Double)s.get("bestOdds"), mp=(Double)s.get("marketProb");
            double p=(Double)s.get("modelProb"), se=odds == null ? Double.NaN : (Double)s.get("evSePct") / (100 * odds);
            boolean fresh = odds != null && !Boolean.TRUE.equals(s.get("stale")); String evidence=(String)s.get("evidence");
            candidates.add(new Staking.Candidate(id, String.valueOf(s.get("gameId")), p, mp == null ? 0 : mp, se, odds == null ? 0 : odds, fresh, experimental, evidence));
            Map<String,Object> row = new LinkedHashMap<>(s); row.put("planStatus", "NO_BET");
            row.put("reasonCode", reason(s, experimental, fresh)); row.put("oMin", odds == null || mp == null ? null : Kelly.minimumOdds(Kelly.conservativeProbability(p,mp,se,state.limits().probabilityShrinkWeight()),state.limits().minEvFraction()));
            row.put("stakeCents", 0L); row.put("howToAct", checklist()); rows.add(row); byId.put(id,row);
        }
        Staking.Plan sizing = Staking.plan(state.balanceCents(), candidates, state.limits(), false, "LIVE_PLANNING".equals(state.mode()));
        for (Staking.Item item : sizing.items()) { Map<String,Object> row=byId.get(item.id()); if(row!=null) row.put("stakeCents",item.stakeCents()); }
        rows.sort(Comparator.comparing(r -> (Instant)r.get("startUtc")));
        Map<String,Object> out=new LinkedHashMap<>(); out.put("date",date); out.put("timezone",HELSINKI.getId()); out.put("mode",state.mode()); out.put("gatesPassed",false); out.put("gateFailures",gates); out.put("items",rows);
        out.put("summary",Map.of("plannedStakeCents",0,"candidateCount",rows.size(),"noBetCount",rows.size(),"note","No stake is planned until validation gates prove a durable edge."));
        out.put("howToAct",checklist()); out.put("endOfDayReview",List.of("Tarkista toteutuneet tulokset ja ennusteselvitykset Historiasta.","Tarkista CLV vain kun käyttökelpoinen sulkeutumislinja on tallennettu; puuttuva CLV ei ole nolla.","Vertaa suunnitelmaa toteutuneisiin, itse kirjattuihin vetoihin — älä kasvata panosta tappion jälkeen.")); return out;
    }
    private static String reason(Map<String,Object> s, boolean experimental, boolean fresh) { if(experimental)return "MODEL_EXPERIMENTAL"; if(!fresh)return "STALE_ODDS"; if(!String.valueOf(s.get("classification")).startsWith("POSITIVE_EV")) return "NO_VALUE_IDENTIFIED"; return "GATES_FAILED"; }
    private static List<String> checklist(){return List.of("Varmista, että paras saatavilla oleva kerroin on edelleen vähintään o_min.","Tarkista vahvistetut maalivahdit ja muut uudet tiedot; tässä sovelluksessa niiden tila on UNKNOWN.","Jos toimit, tee se itse — sovellus ei koskaan välitä vetoja.","Kirjaa itse tehty veto sovellukseen vasta erillisen vahvistuksen jälkeen.");}
}
