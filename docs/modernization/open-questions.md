# Open questions and proposed follow-up tickets

Proposed tickets are recorded here instead of being filed in Jira. Each has a title, context and
proposed owner.

## AB-521 (target data model)

### Proposed: Decide the fate of POLNEW policy-number truncation
- **Context:** POLNEW builds the number with `STRING 'POL' WS-POLICY-SEQ DELIMITED BY SIZE INTO
  POLICY-NUMBER` where `WS-POLICY-SEQ` is `PIC 9(10)` and `POLICY-NUMBER` is `PIC X(12)`. 13
  characters into 12 drops the last digit (verified with GnuCOBOL: sequence 1000000 and 1000007 both
  give `POL000100000`). Ten consecutive sequence values map to one policy number, so 9 in 10 inserts
  hit the primary key. AB-521 ports the format at parity (`PolicyNumbers.fromSequence`) and keeps
  `pas.policy_seq` identical to DB2. Fixing it (e.g. `POL` + 9 digits of the sequence without
  truncation, or skip-ahead) changes policy numbers, so it needs a quirk-register decision before
  AB-525 (new business) ships. Also: `java-facade` sample data uses a different format
  (`POL-00000001`).
- **Proposed owner:** PO + mainframe team (decision), AB-525 implementer.

### Proposed: Load filed territory and class factors
- **Context:** `pas.territory_factors` / `pas.class_factors` have a `FILED` factor set that ships
  empty: neither the COBOL nor DB2 DDL contains real factor values. Rating stays on `PARITY` (all
  1.0000) by default via `pas.rating.factor-set`. Someone has to provide the filed tables and the
  effective dates, and sign off turning them on.
- **Proposed owner:** Rating/actuarial (data), AB-522 follow-up (flag).

### Proposed: Agree the Claims read interface
- **Context:** see `claims-interface-assumptions.md`. Needed before AB-525/AB-526 port UNDWRT and
  POLRNW claims checks.
- **Proposed owner:** Claims team + PAS tech lead.

### Proposed: Security review of PII handling
- **Context:** see `pii-handling.md`. Open items: KMS/secret store and rotation, whether
  name/address/phone/email also need field encryption, masking rules for extracts.
- **Proposed owner:** Security.

### Proposed: Reconcile ADR numbering
- **Context:** `docs/adr/0002-pas-target-data-model-and-pii.md` was numbered without seeing the
  AB-518 target-architecture ADR; renumber when they meet on the integration branch.
- **Proposed owner:** Coordinator.
