# COBOL characterization and parity harness

The harness characterizes the four legacy programs without changing their source. It extracts each
whole program, replaces only the SQL/CICS `EXEC` blocks named in its `execRules`, applies explicit
rewrites from the program spec, and generates a fixed-format GnuCOBOL driver under the ignored
`parity/build/` directory. Every fixture runs in a separate process using fixed-width input and
output records. The generated source keeps legacy paragraphs verbatim outside the specified
substitutions and rewrites.

The specs in `programs/<PROGRAM>/spec.json` define input layouts, output fields, SQL/CICS block
rules, and expected match counts. Hand-written `stubs.cpy` files provide test inputs and capture
database, queue, and screen writes. `source.lock` records SHA-256 digests of each program and its
copied copybooks. `python3 parity/tools/harness.py check` checks those digests, fixture layouts,
and fixture/golden pairing without requiring `cobc`.

PREMBAT's report-file assignment is redirected to `PX_REPORT` under the per-fixture build
directory because the GnuCOBOL runner does not apply its `DD_PREMRPT` environment mapping.

To review a source change, inspect the generated results before updating a lock:

1. Run `python3 parity/tools/harness.py golden --program <PROGRAM>`.
2. Review the generated driver, fixture results, and any `expectLegacy` assertion failures.
3. Run `python3 parity/tools/harness.py lock` only after reviewing the change.
4. Run `make parity` and `make parity-golden-check`.

Fixtures are JSON objects with a description, a partial input object merged over spec defaults,
optional seed values for working-storage fields, and optional `expectLegacy` assertions. Numeric
fixture values are loaded as `Decimal`; fixed-width I/O fields reject out-of-range values rather
than truncating them before the COBOL program executes. Goldens are deterministic JSON with every
compared output type, trace state, events, and the process return code. Decimal values are emitted
as exact-scale JSON numbers, never strings or floating-point values.

GnuCOBOL defaults are used without an IBM dialect flag. GnuCOBOL 3.1.2 golden output characterizes
its intermediate precision, size-error, and data-exception behavior; it is not a substitute for
Enterprise COBOL captures. Confirm or replace these goldens with mainframe captures when available.

`known-differences.yaml` is limited to demonstrated COBOL/Java differences. Each entry identifies a
fixture, output field, quirk-register decision, and reason. The Java runner validates that each
referenced quirk exists, has a non-Preserve decision, and is still exercised by the named fixture.

## Adding a program, fixture, and Java adapter

1. Add the program spec, hand-written stubs, source lock, fixture JSONs, and expected output layout.
2. Generate and review goldens with `make parity-golden`; run twice and confirm the second run has
   no diff.
3. Add a test-scoped `ParityAdapter` implementation in `pas-parity` and exercise it through the
   dynamic fixture runner.
4. Add a known difference only for a verified result that has a Fix decision in the quirk register.
5. Remove the program's disabled parity-test stub after its adapter is implemented and run the
   relevant parity and Maven verification commands.

Later program integrations should follow the planned AB-522 premium-rating and AB-526 renewal/
endorsement work, and should replace the AB-525 underwriting placeholder with the service
implementation.
