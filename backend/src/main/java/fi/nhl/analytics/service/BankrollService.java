package fi.nhl.analytics.service;

import fi.nhl.analytics.core.RiskLimits;
import fi.nhl.analytics.domain.*;
import fi.nhl.analytics.domain.Repos.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Local-only manual ledger. This service never calls a bookmaker or payment provider. */
@Service
public class BankrollService {
    private final BankrollSettingsRepo settings; private final BankrollLedgerRepo ledger;
    public BankrollService(BankrollSettingsRepo settings, BankrollLedgerRepo ledger) { this.settings = settings; this.ledger = ledger; }
    private BankrollSettingsEntity current() { return settings.findById(1L).orElseGet(() -> settings.save(new BankrollSettingsEntity())); }
    public record State(long balanceCents, String mode, RiskLimits limits) {}
    public State state() { BankrollSettingsEntity s = current(); return new State(ledger.findAll().stream().mapToLong(x -> x.amountCents).sum(), s.mode,
            new RiskLimits(s.reserveFraction, s.maxPerBetFraction, s.maxDailyExposureFraction, s.kellyMultiplier, s.probabilityShrinkWeight, s.minEvFraction, s.minimumStakeCents, s.roundingIncrementCents)); }
    public Map<String, Object> get() {
        BankrollSettingsEntity s = current(); List<BankrollLedgerEntity> rows = ledger.findAllByOrderByCreatedAtDesc();
        long balance = rows.stream().mapToLong(x -> x.amountCents).sum();
        Map<String, Object> r = new LinkedHashMap<>(); r.put("mode", s.mode); r.put("balanceCents", balance);
        r.put("settings", settingsMap(s)); r.put("ledger", rows); r.put("notice", "Manual local ledger only. The app never deposits, withdraws, places bets, or accesses betting accounts."); return r;
    }
    public Map<String, Object> update(Map<String, Object> input) {
        BankrollSettingsEntity s = current(); String mode = text(input.get("mode"));
        if (!mode.isBlank()) { if (!List.of("PAPER", "LIVE_PLANNING").contains(mode)) throw new IllegalArgumentException("mode must be PAPER or LIVE_PLANNING"); s.mode = mode; }
        s.reserveFraction = fraction(input, "reserveFraction", s.reserveFraction); s.maxPerBetFraction = fraction(input, "maxPerBetFraction", s.maxPerBetFraction);
        s.maxDailyExposureFraction = fraction(input, "maxDailyExposureFraction", s.maxDailyExposureFraction); s.kellyMultiplier = fraction(input, "kellyMultiplier", s.kellyMultiplier);
        s.probabilityShrinkWeight = fraction(input, "probabilityShrinkWeight", s.probabilityShrinkWeight); s.minEvFraction = fraction(input, "minEvFraction", s.minEvFraction);
        s.minimumStakeCents = cents(input, "minimumStakeCents", s.minimumStakeCents, true); s.roundingIncrementCents = cents(input, "roundingIncrementCents", s.roundingIncrementCents, false);
        new RiskLimits(s.reserveFraction, s.maxPerBetFraction, s.maxDailyExposureFraction, s.kellyMultiplier, s.probabilityShrinkWeight, s.minEvFraction, s.minimumStakeCents, s.roundingIncrementCents);
        settings.save(s); return get();
    }
    public Map<String, Object> transaction(Map<String, Object> input) {
        String type = text(input.get("type")); if (!List.of("DEPOSIT", "WITHDRAWAL", "ADJUSTMENT").contains(type)) throw new IllegalArgumentException("type must be DEPOSIT, WITHDRAWAL or ADJUSTMENT");
        if (!input.containsKey("amountCents")) throw new IllegalArgumentException("amountCents is required");
        long amount = Long.parseLong(String.valueOf(input.get("amountCents"))); if (amount == 0) throw new IllegalArgumentException("amountCents must be non-zero");
        if ("DEPOSIT".equals(type) && amount < 0 || "WITHDRAWAL".equals(type) && amount > 0) throw new IllegalArgumentException("amountCents sign does not match transaction type");
        BankrollLedgerEntity e = new BankrollLedgerEntity(); e.createdAt = Instant.now(); e.type = type; e.amountCents = amount; e.note = text(input.get("note")); ledger.save(e); return get();
    }
    private static Map<String, Object> settingsMap(BankrollSettingsEntity s) { return Map.of("reserveFraction",s.reserveFraction,"maxPerBetFraction",s.maxPerBetFraction,"maxDailyExposureFraction",s.maxDailyExposureFraction,"kellyMultiplier",s.kellyMultiplier,"probabilityShrinkWeight",s.probabilityShrinkWeight,"minEvFraction",s.minEvFraction,"minimumStakeCents",s.minimumStakeCents,"roundingIncrementCents",s.roundingIncrementCents); }
    private static String text(Object x) { return x == null ? "" : String.valueOf(x).trim(); }
    private static double fraction(Map<String,Object> in, String key, double old) { if (!in.containsKey(key)) return old; double d = Double.parseDouble(String.valueOf(in.get(key))); if (!Double.isFinite(d) || d < 0 || d > 1) throw new IllegalArgumentException(key + " must be between 0 and 1"); return d; }
    private static long cents(Map<String,Object> in, String key, long old, boolean zeroOk) { if (!in.containsKey(key)) return old; long v = Long.parseLong(String.valueOf(in.get(key))); if (zeroOk ? v < 0 : v <= 0) throw new IllegalArgumentException(key + " is invalid"); return v; }
}
