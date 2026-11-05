# Architecture decision records

Architecture decisions for the PAS modernization (epic AB-516). Each ADR records the context, the options considered and the decision. ADRs that need Architecture Review Board approval have a submission pack under [`arb/`](arb/).

| ADR | Title | Status | Ticket | ARB submission |
|---|---|---|---|---|
| [0001](0001-pas-target-architecture.md) | PAS target architecture | Proposed | AB-518 | [arb/0001-arb-submission.md](arb/0001-arb-submission.md) |

## Conventions

- File name `NNNN-short-title.md`; numbers are never reused. A changed decision gets a new ADR that supersedes the old one (`Status: Superseded by NNNN`).
- Header fields: Status (`Proposed`, `Accepted`, `Rejected`, `Deprecated`, `Superseded by NNNN`), Date, Ticket, Deciders.
- Required sections: Context, Decision drivers, Decisions, Consequences. Each decision `### Dn: ...` has an "Alternatives considered" table with the chosen option and at least one rejected option.
- New components and data stores go into the ADR's `NNNN-components.json` register; the tables in the ADR and the ARB pack are generated from it.
- References to quirks (`Q-..`), follow-ups (`FU-..`), integrations (`I-..`) and epic tickets must exist in [`docs/modernization/`](../modernization/README.md).

```
make adr                                  # check ADRs, component register, generated tables and references
python3 tools/adr/check_adr.py --write    # regenerate the tables after editing a components file
```

CI (`.github/workflows/adr-check.yml`) runs `make adr` on pull requests into `main` and `devin/ab-516-v3`.
