package fi.nhl.analytics.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "bankroll_ledger")
public class BankrollLedgerEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Instant createdAt;
    /** Signed cents. Permitted user transaction types are DEPOSIT, WITHDRAWAL and ADJUSTMENT. */
    public String type;
    public long amountCents;
    public String note;
}
